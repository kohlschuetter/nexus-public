/*
 * Sonatype Nexus (TM) Open Source Version
 * Copyright (c) 2008-present Sonatype, Inc.
 * All rights reserved. Includes the third-party code listed at http://links.sonatype.com/products/nexus/oss/attributions.
 *
 * This program and the accompanying materials are made available under the terms of the Eclipse Public License Version 1.0,
 * which accompanies this distribution and is available at http://www.eclipse.org/legal/epl-v10.html.
 *
 * Sonatype Nexus (TM) Professional Version is available from Sonatype, Inc. "Sonatype" and "Sonatype Nexus" are trademarks
 * of Sonatype, Inc. Apache Maven is a trademark of the Apache Software Foundation. M2eclipse is a trademark of the
 * Eclipse Foundation. All other trademarks are the property of their respective owners.
 */
package org.sonatype.nexus.content.maven.internal.recipe;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import javax.inject.Inject;
import javax.inject.Named;
import javax.validation.constraints.NotNull;

import org.sonatype.nexus.blobstore.api.Blob;
import org.sonatype.nexus.common.event.EventManager;
import org.sonatype.nexus.common.hash.HashAlgorithm;
import org.sonatype.nexus.content.maven.MavenContentFacet;
import org.sonatype.nexus.content.maven.internal.event.RebuildMavenArchetypeCatalogEvent;
import org.sonatype.nexus.content.maven.store.GAV;
import org.sonatype.nexus.content.maven.store.Maven2ComponentData;
import org.sonatype.nexus.content.maven.store.Maven2ComponentStore;
import org.sonatype.nexus.mime.MimeSupport;
import org.sonatype.nexus.repository.Repository;
import org.sonatype.nexus.repository.config.Configuration;
import org.sonatype.nexus.repository.config.ConfigurationFacet;
import org.sonatype.nexus.repository.config.WritePolicy;
import org.sonatype.nexus.repository.content.Asset;
import org.sonatype.nexus.repository.content.Component;
import org.sonatype.nexus.repository.content.RepositoryContent;
import org.sonatype.nexus.repository.content.facet.ContentFacet;
import org.sonatype.nexus.repository.content.facet.ContentFacetSupport;
import org.sonatype.nexus.repository.content.fluent.FluentAsset;
import org.sonatype.nexus.repository.content.fluent.FluentAssetBuilder;
import org.sonatype.nexus.repository.content.fluent.FluentComponent;
import org.sonatype.nexus.repository.content.store.ComponentStore;
import org.sonatype.nexus.repository.content.store.FormatStoreManager;
import org.sonatype.nexus.repository.maven.LayoutPolicy;
import org.sonatype.nexus.repository.maven.MavenPath;
import org.sonatype.nexus.repository.maven.MavenPath.Coordinates;
import org.sonatype.nexus.repository.maven.MavenPath.HashType;
import org.sonatype.nexus.repository.maven.MavenPathParser;
import org.sonatype.nexus.repository.maven.VersionPolicy;
import org.sonatype.nexus.repository.maven.internal.Maven2Format;
import org.sonatype.nexus.repository.maven.internal.hosted.metadata.MetadataRebuilder;
import org.sonatype.nexus.repository.maven.internal.validation.MavenMetadataContentValidator;
import org.sonatype.nexus.repository.types.HostedType;
import org.sonatype.nexus.repository.types.ProxyType;
import org.sonatype.nexus.repository.view.Content;
import org.sonatype.nexus.repository.view.Payload;
import org.sonatype.nexus.repository.view.payloads.TempBlob;

import com.google.common.collect.ImmutableMap;
import com.google.common.hash.HashCode;
import org.apache.commons.lang3.StringUtils;
import org.apache.maven.model.Model;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.util.stream.Collectors.toSet;
import static org.apache.commons.lang3.StringUtils.prependIfMissing;
import static org.sonatype.nexus.blobstore.api.BlobStore.BLOB_NAME_HEADER;
import static org.sonatype.nexus.blobstore.api.BlobStore.CONTENT_TYPE_HEADER;
import static org.sonatype.nexus.common.hash.HashAlgorithm.SHA1;
import static org.sonatype.nexus.content.maven.internal.recipe.MavenArchetypeCatalogFacetImpl.MAVEN_ARCHETYPE_KIND;
import static org.sonatype.nexus.content.maven.internal.recipe.MavenAttributesHelper.assetKind;
import static org.sonatype.nexus.content.maven.internal.recipe.MavenAttributesHelper.setMavenAttributes;
import static org.sonatype.nexus.repository.config.WritePolicy.ALLOW;
import static org.sonatype.nexus.repository.config.WritePolicy.ALLOW_ONCE;
import static org.sonatype.nexus.repository.content.AttributeOperation.OVERLAY;
import static org.sonatype.nexus.repository.maven.MavenMetadataRebuildFacet.METADATA_FORCE_REBUILD;
import static org.sonatype.nexus.repository.maven.MavenMetadataRebuildFacet.METADATA_REBUILD;
import static org.sonatype.nexus.repository.maven.internal.Attributes.AssetKind.ARTIFACT_SUBORDINATE;
import static org.sonatype.nexus.repository.maven.internal.Attributes.AssetKind.REPOSITORY_INDEX;
import static org.sonatype.nexus.repository.maven.internal.Attributes.AssetKind.REPOSITORY_METADATA;
import static org.sonatype.nexus.repository.maven.internal.Attributes.P_ARTIFACT_ID;
import static org.sonatype.nexus.repository.maven.internal.Attributes.P_BASE_VERSION;
import static org.sonatype.nexus.repository.maven.internal.Attributes.P_CLASSIFIER;
import static org.sonatype.nexus.repository.maven.internal.Attributes.P_EXTENSION;
import static org.sonatype.nexus.repository.maven.internal.Attributes.P_GROUP_ID;
import static org.sonatype.nexus.repository.maven.internal.Attributes.P_VERSION;
import static org.sonatype.nexus.repository.maven.internal.Constants.METADATA_FILENAME;
import static org.sonatype.nexus.repository.maven.internal.Maven2Format.NAME;
import static org.sonatype.nexus.repository.maven.internal.MavenModels.readModel;
import static org.sonatype.nexus.repository.maven.internal.hosted.metadata.MetadataUtils.metadataPath;

/**
 * A {@link MavenContentFacet} that persists to a {@link ContentFacet}.
 *
 * @since 3.25
 */
@Named(NAME)
public class MavenContentFacetImpl
    extends ContentFacetSupport
    implements MavenContentFacet
{
  private static final char ASSET_PATH_PREFIX = '/';

  private static final String CONFIG_KEY = "maven";

  private final Map<String, MavenPathParser> mavenPathParsers;

  private final MavenMetadataContentValidator metadataValidator;

  private final EventManager eventManager;

  private final boolean metadataValidationEnabled;

  private Config config;

  private MavenPathParser mavenPathParser;

  private MetadataRebuilder metadataRebuilder;

  private MimeSupport mimeSupport;

  static class Config
  {
    @NotNull(groups = {HostedType.ValidationGroup.class, ProxyType.ValidationGroup.class})
    public VersionPolicy versionPolicy;

    @NotNull(groups = {HostedType.ValidationGroup.class, ProxyType.ValidationGroup.class})
    public LayoutPolicy layoutPolicy;

    @Override
    public String toString() {
      return getClass().getSimpleName() + "{" +
          "versionPolicy=" + versionPolicy +
          ", layoutPolicy=" + layoutPolicy +
          '}';
    }
  }

  @Inject
  public MavenContentFacetImpl(
      @Named(NAME) final FormatStoreManager formatStoreManager,
      final Map<String, MavenPathParser> mavenPathParsers,
      final MetadataRebuilder metadataRebuilder,
      final MavenMetadataContentValidator metadataValidator,
      final EventManager eventManager,
      final MimeSupport mimeSupport,
      @Named("${nexus.maven.metadata.validation.enabled:-true}") final boolean metadataValidationEnabled)
  {
    super(formatStoreManager);
    this.mavenPathParsers = checkNotNull(mavenPathParsers);
    this.metadataRebuilder = checkNotNull(metadataRebuilder);
    this.metadataValidator = metadataValidator;
    this.eventManager = eventManager;
    this.mimeSupport = checkNotNull(mimeSupport);
    this.metadataValidationEnabled = metadataValidationEnabled;
  }

  @Override
  public MavenPathParser getMavenPathParser() {
    return mavenPathParser;
  }

  @Override
  public LayoutPolicy layoutPolicy() {
    return config.layoutPolicy;
  }

  @Override
  public VersionPolicy getVersionPolicy() {
    return config.versionPolicy;
  }

  @Override
  protected void doInit(final Configuration configuration) throws Exception {
    super.doInit(configuration);
    mavenPathParser = checkNotNull(mavenPathParsers.get(getRepository().getFormat().getValue()));
  }

  @Override
  protected void doConfigure(final Configuration configuration) throws Exception {
    super.doConfigure(configuration);
    config = facet(ConfigurationFacet.class).readSection(configuration, CONFIG_KEY, MavenContentFacetImpl.Config.class);
    log.debug("Config: {}", config);
  }

  @Override
  protected WritePolicy writePolicy(final Asset asset) {
    WritePolicy configuredWritePolicy = super.writePolicy(asset);
    if (ALLOW_ONCE == configuredWritePolicy) {
      String assetKind = asset.kind();
      if (StringUtils.equals(REPOSITORY_METADATA.name(), assetKind)
          || StringUtils.equals(REPOSITORY_INDEX.name(), assetKind)
          || StringUtils.equals(ARTIFACT_SUBORDINATE.name(), assetKind)) {
        return ALLOW;
      }
    }
    return configuredWritePolicy;
  }

  @Override
  public Optional<Content> get(final MavenPath mavenPath) {
    log.debug("GET {} : {}", getRepository().getName(), mavenPath);

    return findAsset(assetPath(mavenPath))
        .map(FluentAsset::download);
  }

  @Override
  public Content put(final MavenPath mavenPath, final Payload content) throws IOException {
    log.debug("PUT {} : {}", getRepository().getName(), mavenPath);

    try (TempBlob blob = blobs().ingest(content, HashType.ALGORITHMS)) {
      if (isMetadataAndValidationEnabled(mavenPath)) {
        validate(mavenPath, blob);
      }
      return save(mavenPath, content, blob);
    }
  }

  @Override
  public void hardLink(FluentAsset asset, Path contentPath) throws IOException {
    String mimeType = mimeSupport.detectMimeType(Files.newInputStream(contentPath), contentPath.toString());

    Map<String, String> headers = ImmutableMap.of(
        BLOB_NAME_HEADER, contentPath.toString(),
        CONTENT_TYPE_HEADER, mimeType
    );

    byte[] bytes = Files.readAllBytes(contentPath);
    Map<HashAlgorithm, HashCode> hashes = HashType.ALGORITHMS.stream().collect(Collectors.toMap(
        Function.identity(),
        a -> a.function().hashBytes(bytes)
    ));
    Blob blob = blobs().ingest(contentPath, headers, hashes.get(SHA1), Files.size(contentPath));

    asset.attach(blob, hashes);
  }

  private Optional<FluentAsset> findAsset(final String path) {
    return assets()
        .path(path)
        .find();
  }

  private boolean isMetadataAndValidationEnabled(final MavenPath mavenPath) {
    return mavenPath.getFileName().equals(METADATA_FILENAME) && metadataValidationEnabled;
  }

  private void validate(final MavenPath mavenPath, final TempBlob blob) {
    log.debug("Validating maven-metadata.xml before storing");
    try (InputStream in = blob.get()) {
      metadataValidator.validate(mavenPath.getPath(), in);
    }
    catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private Content save(final MavenPath mavenPath, final Payload content, final TempBlob blob) throws IOException {
    FluentComponent component = null;
    if (mavenPath.getCoordinates() != null) {
      Optional<Model> model = maybeReadMavenModel(mavenPath, blob);
      component = createOrGetComponent(mavenPath, model);
    }
    return saveAsset(mavenPath, component, content, blob);
  }

  private FluentComponent createOrGetComponent(final MavenPath mavenPath, final Optional<Model> model)
  {
    Optional<String> optionalKind = model.map(MavenAttributesHelper::getPackaging);
    Coordinates coordinates = mavenPath.getCoordinates();

    FluentComponent component = components()
        .name(coordinates.getArtifactId())
        .namespace(coordinates.getGroupId())
        .version(coordinates.getVersion())
        .kind(optionalKind)
        .getOrCreate();

    boolean isNew = isNewRepositoryContent(component);
    MavenAttributesHelper.setMavenAttributes(
          (Maven2ComponentStore) stores().componentStore, component, coordinates, model, contentRepositoryId());

    if (isNew) {
      publishEvents(component);
    }
    else {
      // kind isn't set for existing components
      optionalKind.ifPresent(component::kind);
    }
    return component;
  }

  private boolean isNewRepositoryContent(final RepositoryContent repositoryContent) {
    return repositoryContent.attributes().isEmpty();
  }

  @Override
  public void maybeUpdateComponentAttributes(final MavenPath mavenPath) throws IOException
  {
    if (mavenPath.isPom()) {
      Optional<FluentAsset> optAsset = assets().path(assetPath(mavenPath)).find();
      if (optAsset.isPresent()) {
        FluentAsset asset = optAsset.get();
        Model model = readModel(asset.download().openInputStream());
        createOrGetComponent(mavenPath, Optional.ofNullable(model));
      }
    }
  }

  private void publishEvents(final FluentComponent component) {
    if (MAVEN_ARCHETYPE_KIND.equals(component.kind())) {
      eventManager.post(new RebuildMavenArchetypeCatalogEvent(getRepository().getName()));
    }
  }

  private Optional<Model> maybeReadMavenModel(final MavenPath mavenPath, final TempBlob blob) throws IOException
  {
    Model model = null;
    if (mavenPath.isPom()) {
      model = readModel(blob.getBlob().getInputStream());
      if (model == null) {
        log.warn("Could not parse POM: {} @ {}", getRepository().getName(), assetPath(mavenPath));
      }
    }
    return Optional.ofNullable(model);
  }

  private Content saveAsset(
      final MavenPath mavenPath,
      final Component component,
      final Payload content,
      final TempBlob blob)
  {
    String path = assetPath(mavenPath);
    FluentAssetBuilder assetBuilder = assets().path(path).kind(assetKind(mavenPath, mavenPathParser));
    if (component != null) {
      assetBuilder = assetBuilder.component(component);
    }

    FluentAsset asset = assetBuilder.blob(blob).save();

    if (isNewRepositoryContent(asset)) {
      setMavenAttributes(asset, mavenPath);
    }

    return asset
        .markAsCached(content)
        .download();
  }

  private String assetPath(final MavenPath mavenPath) {
    return ASSET_PATH_PREFIX + mavenPath.getPath();
  }

  @Override
  public boolean delete(final MavenPath mavenPath) {
    log.trace("DELETE {} : {}", getRepository().getName(), mavenPath);
    boolean assetIsDeleted = deleteAsset(mavenPath);
    if (assetIsDeleted && mavenPath.getCoordinates() != null) {
      maybeDeleteComponent(mavenPath.getCoordinates());
    }
    return assetIsDeleted;
  }

  @Override
  public boolean delete(final List<String> paths) {
    Repository repository = getRepository();
    log.trace("DELETE {} assets at {}", repository.getName(), paths);
    return stores().assetStore.deleteAssetsByPaths(contentRepositoryId(), paths) > 0;
  }

  @Override
  public Set<String> deleteWithHashes(final MavenPath mavenPath) {
    final Set<String> paths = new HashSet<>(HashType.values().length + 1);
    if (delete(mavenPath.main())) {
      paths.add(mavenPath.main().getPath());
    }
    for (HashType hashType : HashType.values()) {
      MavenPath hashMavenPath = mavenPath.main().hash(hashType);
      if (delete(hashMavenPath)) {
        paths.add(hashMavenPath.getPath());
      }
    }
    return paths;
  }

  @Override
  public boolean exists(final MavenPath mavenPath) {
    return findAsset(assetPath(mavenPath)).isPresent();
  }

  private boolean deleteAsset(final MavenPath mavenPath) {
    return findAsset(assetPath(mavenPath))
        .map(FluentAsset::delete)
        .orElse(false);
  }

  private void maybeDeleteComponent(final Coordinates coordinates) {
    components()
        .name(coordinates.getArtifactId())
        .namespace(coordinates.getGroupId())
        .version(coordinates.getVersion())
        .find()
        .ifPresent(this::deleteIfNoAssetsLeft);
  }

  private void deleteIfNoAssetsLeft(final FluentComponent component) {
    if (component.assets().isEmpty()) {
      component.delete();
      publishEvents(component);
      deleteMetadataOrFlagForRebuild(component);
    }
  }

  @Override
  public int deleteComponents(final int[] componentIds) {
    ContentFacetSupport contentFacet = (ContentFacetSupport) facet(ContentFacet.class);
    ComponentStore<?> componentStore = contentFacet.stores().componentStore;
    if (!ProxyType.NAME.equals(repository().getType().getValue())) {
      Set<List<String>> gavs = collectGavs(componentIds);
      int deletedCount = componentStore.purge(contentFacet.contentRepositoryId(), componentIds);
      gavs.forEach(gav -> deleteMetadataOrFlagForRebuild(gav.get(0), gav.get(1), gav.get(2)));
      return deletedCount;
    }
    else {
      return componentStore.purge(contentFacet.contentRepositoryId(), componentIds);
    }
  }

  private Set<List<String>> collectGavs(final int[] componentIds) {
    ContentFacetSupport contentFacet = (ContentFacetSupport) facet(ContentFacet.class);
    ComponentStore<?> componentStore = contentFacet.stores().componentStore;
    return Arrays.stream(componentIds)
        .mapToObj(componentStore::readComponent)
        .filter(Optional::isPresent)
        .map(Optional::get)
        .map(this::collectGabv)
        .map(Arrays::asList)
        .collect(toSet());
  }

  private String[] collectGabv(Component component) {
    return new String[]{
        component.namespace(), component.name(),
        component.attributes(NAME).get(P_BASE_VERSION, String.class)
    };
  }

  @Override
  public Set<String> deleteMetadataOrFlagForRebuild(final Component component) {
    if (!ProxyType.NAME.equals(repository().getType().getValue())) {
      String[] gav = collectGabv(component);
      return deleteMetadataOrFlagForRebuild(gav[0], gav[1], gav[2]);
    }
    return Collections.emptySet();
  }

  /**
   * @return set of deleted metadata assets' paths
   */
  private Set<String> deleteMetadataOrFlagForRebuild(
      final String groupId,
      final String artifactId,
      final String baseVersion)
  {
    checkNotNull(groupId);
    checkNotNull(artifactId);
    checkNotNull(baseVersion);

    List<String[]> metadataCoordinatesToDelete = new ArrayList<>();
    ImmutableMap<String, Object> gabvQueryParameters =
        ImmutableMap.of("groupId", groupId, "artifactId", artifactId, "baseVersion", baseVersion);
    boolean gNonEmpty = components().byFilter("namespace = #{filterParams.groupId}", gabvQueryParameters).count() > 0;
    boolean gaNonEmpty = components()
        .byFilter("namespace = #{filterParams.groupId} AND name = #{filterParams.artifactId}", gabvQueryParameters)
        .count() > 0;
    boolean gabvNonEmpty = components().byFilter(
        "namespace = #{filterParams.groupId} AND name = #{filterParams.artifactId} AND base_version = #{filterParams.baseVersion}",
        gabvQueryParameters).count() > 0;

    if (gabvNonEmpty) {
      flagForMetadataRebuild(groupId, artifactId, baseVersion);
    }
    else if (gaNonEmpty) {
      metadataCoordinatesToDelete.add(new String[]{groupId, artifactId, baseVersion});
      flagForMetadataRebuild(groupId, artifactId, null);
    }
    else if (gNonEmpty) {
      metadataCoordinatesToDelete.add(new String[]{groupId, artifactId, baseVersion});
      metadataCoordinatesToDelete.add(new String[]{groupId, artifactId, null});
      flagForMetadataRebuild(groupId, null, null);
    }
    else {
      metadataCoordinatesToDelete.add(new String[]{groupId, artifactId, baseVersion});
      metadataCoordinatesToDelete.add(new String[]{groupId, artifactId, null});
      metadataCoordinatesToDelete.add(new String[]{groupId, null, null});
    }

    return metadataRebuilder.deleteMetadata(getRepository(), metadataCoordinatesToDelete);
  }

  private void flagForMetadataRebuild(final String groupId, final String artifactId, final String baseVersion) {
    assets()
        .path(prependIfMissing(metadataPath(groupId, artifactId, baseVersion).getPath(), "/"))
        .find()
        .ifPresent(this::setMetadataRebuildFlag);
  }

  private void setMetadataRebuildFlag(FluentAsset asset) {
    Map<String, Object> metadataRebuild = new HashMap<>();
    metadataRebuild.put(METADATA_FORCE_REBUILD, true);
    asset.withAttribute(METADATA_REBUILD, metadataRebuild);
  }

  public Set<GAV> findGavsWithSnaphots(final int minimumRetained) {
    Maven2ComponentStore componentStore = (Maven2ComponentStore)stores().componentStore;
    return componentStore.findGavsWithSnaphots(contentRepositoryId(), minimumRetained);
  }

  public List<Maven2ComponentData> findComponentsForGav(final String name,
                                                       final String group,
                                                       final String baseVersion,
                                                       final String releaseVersion) {
    Maven2ComponentStore componentStore = (Maven2ComponentStore) stores().componentStore;
    return componentStore.findComponentsForGav(contentRepositoryId(), name, group, baseVersion, releaseVersion);
  }

  public int[] selectSnapshotsAfterRelease(final int gracePeriod) {
    Maven2ComponentStore componentStore = (Maven2ComponentStore) stores().componentStore;
    return componentStore.selectSnapshotsAfterRelease(gracePeriod, contentRepositoryId());
  }
  
  public FluentAsset createComponentAndAsset(final MavenPath mavenPath) {
    String assetName = "/" + mavenPath.getPath();
    String assetKind = assetKind(mavenPath, mavenPathParser);

    if (mavenPath.getCoordinates() == null) {
      return assets().path(assetName).kind(assetKind).save();
    }
    else {
      Coordinates coordinates = checkNotNull(mavenPath.getCoordinates());

      FluentComponent component = createOrGetComponent(coordinates);

      FluentAsset asset = component.asset(assetName).kind(assetKind).save();
      configureAssetAttributes(asset, coordinates);

      return asset;
    }
  }

  private FluentComponent createOrGetComponent(Coordinates coordinates) {
    MavenContentFacet facet = getRepository().facet(MavenContentFacet.class);
    final String artifactId = coordinates.getArtifactId();
    final String groupId = coordinates.getGroupId();
    final String version = coordinates.getVersion();
    final String baseVersion = coordinates.getBaseVersion();

    FluentComponent component = facet.components()
        .name(artifactId)
        .namespace(groupId)
        .version(version)
        .getOrCreate();

    ImmutableMap.Builder<String, String> componentAttributes = ImmutableMap.builder();
    componentAttributes.put(P_GROUP_ID, groupId);
    componentAttributes.put(P_ARTIFACT_ID, artifactId);
    componentAttributes.put(P_VERSION, version);
    componentAttributes.put(P_BASE_VERSION, baseVersion);
    component.attributes(OVERLAY, Maven2Format.NAME, componentAttributes.build());

    Maven2ComponentData componentData = new Maven2ComponentData();
    componentData.setNamespace(groupId);
    componentData.setName(artifactId);
    componentData.setVersion(version);
    componentData.setRepositoryId(facet.contentRepositoryId());
    componentData.setBaseVersion(baseVersion);

    Maven2ComponentStore componentStore = (Maven2ComponentStore) stores().componentStore;
    componentStore.updateBaseVersion(componentData);

    return component;
  }

  private void configureAssetAttributes(final FluentAsset asset, final Coordinates coordinates) {
    ImmutableMap.Builder<String, String> assetAttributes = ImmutableMap.builder();
    assetAttributes.put(P_GROUP_ID, coordinates.getGroupId());
    assetAttributes.put(P_ARTIFACT_ID, coordinates.getArtifactId());
    assetAttributes.put(P_VERSION, coordinates.getVersion());
    assetAttributes.put(P_BASE_VERSION, coordinates.getBaseVersion());
    if (coordinates.getClassifier() != null) {
      assetAttributes.put(P_CLASSIFIER, coordinates.getClassifier());
    }
    assetAttributes.put(P_EXTENSION, coordinates.getExtension());
    asset.attributes(OVERLAY, Maven2Format.NAME, assetAttributes.build());
  }
}
