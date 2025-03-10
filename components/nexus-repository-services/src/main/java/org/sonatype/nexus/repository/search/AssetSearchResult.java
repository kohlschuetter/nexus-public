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
package org.sonatype.nexus.repository.search;

import java.util.Date;
import java.util.Map;

/**
 * Result of an Asset search
 *
 * @since 3.next
 */
public class AssetSearchResult
{
  private String path;

  private String id;

  private String repository;

  private String format;

  private Map<String, String> checksum;

  private String contentType;

  private Date lastModified;

  private Map<String, Object> attributes;

  public String getPath() {
    return path;
  }

  public void setPath(final String path) {
    this.path = path;
  }

  public String getId() {
    return id;
  }

  public void setId(final String id) {
    this.id = id;
  }

  public String getRepository() {
    return repository;
  }

  public void setRepository(final String repository) {
    this.repository = repository;
  }

  public String getFormat() {
    return format;
  }

  public void setFormat(final String format) {
    this.format = format;
  }

  public Map<String, String> getChecksum() {
    return checksum;
  }

  public void setChecksum(final Map<String, String> checksum) {
    this.checksum = checksum;
  }

  public String getContentType() {
    return contentType;
  }

  public void setContentType(final String contentType) {
    this.contentType = contentType;
  }

  public Date getLastModified() {
    return lastModified;
  }

  public void setLastModified(final Date lastModified) {
    this.lastModified = lastModified;
  }

  public Map<String, Object> getAttributes() {
    return attributes;
  }

  public void setAttributes(final Map<String, Object> attributes) {
    this.attributes = attributes;
  }
}
