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
package org.sonatype.nexus.node.datastore;

import java.util.Optional;

import org.sonatype.nexus.common.node.NodeAccess;

/**
 * Store for accessing the node id in single node environments.
 *
 * @since 3.37
 */
public interface NodeIdStore
{
  /**
   * Remove the currently persisted node id, this will not change the  {@link NodeAccess}.
   */
  void clear();

  /**
   * Retrieve the current node id if it exists.
   *
   * @return the node id
   */
  Optional<String> get();

  /**
   * Set the current node id, this will not update the {@link NodeAccess}
   * @param nodeId
   */
  void set(final String nodeId);
}
