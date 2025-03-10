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
package org.sonatype.nexus.repository.content.browse;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.common.event.EventManager;
import org.sonatype.nexus.repository.content.event.asset.AssetCreatedEvent;
import org.sonatype.nexus.repository.content.event.asset.AssetDeletedEvent;
import org.sonatype.nexus.repository.content.event.asset.AssetPurgedEvent;
import org.sonatype.nexus.repository.content.event.asset.AssetUploadedEvent;
import org.sonatype.nexus.repository.content.event.component.ComponentDeletedEvent;
import org.sonatype.nexus.repository.content.event.component.ComponentPurgedEvent;
import org.sonatype.nexus.scheduling.PeriodicJobService;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyZeroInteractions;

public class BrowseEventHandlerTest
    extends TestSupport
{
  @Mock
  private PeriodicJobService periodicJobService;

  @Mock
  private EventManager eventManager;

  private BrowseEventHandler underTest;

  @Before
  public void setup() {
    underTest = new BrowseEventHandler(periodicJobService, eventManager, 100, 2, true);
  }

  @Test
  public void testAssetPurgedEvent_notProcessedWhenPaused() {
    underTest.pauseEventProcessing();
    AssetPurgedEvent event = mock(AssetPurgedEvent.class);
    underTest.on(event);
    verifyZeroInteractions(event);
  }

  @Test
  public void testAssetCreatedEvent_notProcessedWhenPaused() {
    underTest.pauseEventProcessing();
    AssetCreatedEvent event = mock(AssetCreatedEvent.class);
    underTest.on(event);
    verifyZeroInteractions(event);
  }

  @Test
  public void testAssetUploadedEvent_notProcessedWhenPaused() {
    underTest.pauseEventProcessing();
    AssetUploadedEvent event = mock(AssetUploadedEvent.class);
    underTest.on(event);
    verifyZeroInteractions(event);
  }

  @Test
  public void testAssetDeletedEvent_notProcessedWhenPaused() {
    underTest.pauseEventProcessing();
    AssetDeletedEvent event = mock(AssetDeletedEvent.class);
    underTest.on(event);
    verifyZeroInteractions(event);
  }

  @Test
  public void testComponentPurgedEvent_notProcessedWhenPaused() {
    underTest.pauseEventProcessing();
    ComponentPurgedEvent event = mock(ComponentPurgedEvent.class);
    underTest.on(event);
    verifyZeroInteractions(event);
  }

  @Test
  public void testComponentDeletedEvent_notProcessedWhenPaused() {
    underTest.pauseEventProcessing();
    ComponentDeletedEvent event = mock(ComponentDeletedEvent.class);
    underTest.on(event);
    verifyZeroInteractions(event);
  }
}
