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
package org.sonatype.nexus.repository.maven.internal.group;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.sonatype.goodies.common.Time;
import org.sonatype.nexus.common.collect.AttributesMap;
import org.sonatype.nexus.common.io.Cooperation;
import org.sonatype.nexus.common.io.Cooperation.IOCall;
import org.sonatype.nexus.common.io.CooperationFactory;
import org.sonatype.nexus.repository.HasFacet;
import org.sonatype.nexus.repository.Repository;
import org.sonatype.nexus.repository.group.GroupFacet;
import org.sonatype.nexus.repository.group.GroupHandler;
import org.sonatype.nexus.repository.http.HttpResponses;
import org.sonatype.nexus.repository.maven.MavenPath;
import org.sonatype.nexus.repository.proxy.ProxyFacet;
import org.sonatype.nexus.repository.view.Content;
import org.sonatype.nexus.repository.view.Context;
import org.sonatype.nexus.repository.view.Response;
import org.sonatype.nexus.transaction.RetryDeniedException;

import com.google.common.base.Predicate;

import static com.google.common.base.Predicates.or;
import static java.util.Optional.empty;
import static java.util.Optional.ofNullable;
import static java.util.stream.Collectors.toList;

/**
 * Maven2 specific group handler: calls into {@link MavenGroupFacet} to get some content from members, cache it, and
 * serve it up. Handles merging of repository metadata and archetype catalog.
 *
 * @since 3.0
 */
@Singleton
@Named
public class MergingGroupHandler
    extends GroupHandler
{
  private static final Predicate<Repository> PROXY_OR_GROUP =
      or(new HasFacet(ProxyFacet.class), new HasFacet(GroupFacet.class));

  @Nullable
  private Cooperation metadataCooperation;

  @Inject
  public MergingGroupHandler(
      final CooperationFactory cooperationFactory,
      @Named("${nexus.maven.group.cooperation.enabled:-true}") final boolean cooperationEnabled,
      @Named("${nexus.maven.group.cooperation.majorTimeout:-0s}") final Time majorTimeout,
      @Named("${nexus.maven.group.cooperation.minorTimeout:-30s}") final Time minorTimeout,
      @Named("${nexus.maven.group.cooperation.threadsPerKey:-100}") final int threadsPerKey)
  {
    if (cooperationEnabled) {
      this.metadataCooperation = cooperationFactory.configure()
          .majorTimeout(majorTimeout)
          .minorTimeout(minorTimeout)
          .threadsPerKey(threadsPerKey)
          .build(getClass().getName());
    }
  }

  protected Response doGetHash(@Nonnull final Context context) throws Exception
  {
    final MavenPath mavenPath = context.getAttributes().require(MavenPath.class);
    final MavenGroupFacet groupFacet = context.getRepository().facet(MavenGroupFacet.class);
    final Repository repository = context.getRepository();
    log.trace("Incoming request for {} : {}", context.getRepository().getName(), mavenPath.getPath());

    //hashes need the parent asset(s) loaded into cache, they are calculated as a side effect of that
    final MavenPath parentPath = mavenPath.subordinateOf();

    //Create a new context to request the parent path and prime the caches with the subordinates
    final Context copyContext = context.copy(
        oldAttributes -> {
          AttributesMap newAttributes = new AttributesMap();
          oldAttributes.backing().forEach(newAttributes::set);
          newAttributes.set(MavenPath.class, parentPath);
          return newAttributes;
        },
        requestBuilder -> {
          requestBuilder.path(parentPath.getPath());

          AttributesMap oldAttributes = requestBuilder.attributes();
          AttributesMap newAttributes = new AttributesMap();
          oldAttributes.backing().forEach(newAttributes::set);
          newAttributes.set(MavenPath.class, parentPath);

          requestBuilder.attributes(newAttributes);
          return requestBuilder;
        });

    doGet(copyContext, new DispatchedRepositories());

    Optional<Content> cachedContent = checkCache(groupFacet, mavenPath, repository);
    if (cachedContent.isPresent()) {
      log.trace("Serving cached content {} : {}", repository.getName(), mavenPath.getPath());
      return HttpResponses.ok(cachedContent.get());
    }
    else {
      // hash should be available if corresponding content fetched. out of bound request?
      log.trace("Outbound request for hash {} : {}", repository.getName(), mavenPath.getPath());
      return HttpResponses.notFound();
    }
  }

  private Response doGetContent(
      @Nonnull final Context context,
      @Nonnull final DispatchedRepositories dispatched) throws Exception
  {
    final MavenPath mavenPath = context.getAttributes().require(MavenPath.class);
    final MavenGroupFacet groupFacet = context.getRepository().facet(MavenGroupFacet.class);
    final Repository repository = context.getRepository();
    final List<Repository> members = groupFacet.members();

    log.trace("Incoming request for {} : {}", context.getRepository().getName(), mavenPath.getPath());

    List<Repository> proxiesOrGroups =
        members.stream().filter(PROXY_OR_GROUP::apply).collect(toList());

    Content content = maybeCooperate(key(repository, mavenPath), __ -> {
      try {
        // Check members to prime
        Map<Repository, Response> passThroughResponses = getAll(context, proxiesOrGroups, dispatched);

        Optional<Content> cached = checkCache(groupFacet, mavenPath, repository);

        if (cached.isPresent()) {
          log.trace("Serving cached content {} : {}", repository.getName(), mavenPath.getPath());
          return cached.get();
        }

        // this will fetch the remaining responses, thanks to the 'dispatched' tracking
        Map<Repository, Response> remainingResponses = getAll(context, members, dispatched);

        // merge the two sets of responses according to member order
        LinkedHashMap<Repository, Response> responses = new LinkedHashMap<>();
        for (Repository member : members) {
          Response response = passThroughResponses.get(member);
          if (response == null) {
            response = remainingResponses.get(member);
          }
          if (response != null) {
            responses.put(member, response);
          }
        }

        // merge the individual responses and cache the result
        Content mergedContent = groupFacet.mergeAndCache(mavenPath, responses);
        if (mergedContent != null) {
          log.trace("Responses merged {} : {}", context.getRepository().getName(), mavenPath.getPath());
        }
        return mergedContent;
      }
      catch (Exception e) {
        throw new IOException(e);
      }
    });


    if (content != null) {
      return HttpResponses.ok(content);
    }
    else {
      log.trace("Not found response to merge {} : {}", repository.getName(), mavenPath.getPath());
      return HttpResponses.notFound();
    }
  }

  private Optional<Content> checkCache(
      @Nonnull final MavenGroupFacet groupFacet,
      @Nonnull final MavenPath mavenPath,
      @Nonnull final Repository repository)
      throws IOException
  {
    try {
      // check group-level cache to see if it's been invalidated by any updates
      return ofNullable(groupFacet.getCached(mavenPath));
    }
    catch (RetryDeniedException e) {
      log.debug("Conflict fetching cached content {} : {}", repository.getName(), mavenPath.getPath(), e);
    }
    return empty();
  }

  @Override
  protected Response doGet(
      @Nonnull final Context context,
      @Nonnull final DispatchedRepositories dispatched) throws Exception
  {
    final MavenPath mavenPath = context.getAttributes().require(MavenPath.class);

    if (mavenPath.isHash()) {
      return doGetHash(context);
    }
    else {
      return doGetContent(context, dispatched);
    }
  }

  /*
   * Invoke the call with co-operation if available, if not invoke the call directly.
   */
  private <T> T maybeCooperate(final String key, final IOCall<T> call) throws IOException {
    if (metadataCooperation == null) {
      return call.call(false);
    }
    return metadataCooperation.cooperate(key, call);
  }

  /*
   * Create a key for use by co-operation
   */
  private static String key(final Repository repository, final MavenPath path) {
    return repository.getName() + ":" + path.toString();
  }
}
