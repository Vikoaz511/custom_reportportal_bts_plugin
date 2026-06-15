/*
 * Copyright 2016 EPAM Systems
 *
 *
 * This file is part of EPAM Report Portal.
 * https://github.com/reportportal/service-jira
 *
 * Report Portal is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Report Portal is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Report Portal.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.epam.reportportal.extension.bugtracking.jira;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.epam.reportportal.model.externalsystem.PostTicketRQ;
import com.epam.ta.reportportal.dao.LogRepository;
import com.epam.ta.reportportal.dao.TestItemRepository;
import com.epam.ta.reportportal.entity.attachment.Attachment;
import com.epam.ta.reportportal.entity.item.TestItem;
import com.epam.ta.reportportal.entity.log.Log;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class TicketDescriptionServiceTest {

  private final LogRepository logRepository = mock(LogRepository.class);
  private final TestItemRepository itemRepository = mock(TestItemRepository.class);
  private final JIRATicketDescriptionService descriptionService =
      new JIRATicketDescriptionService(logRepository, itemRepository);

  @Test
  void testDescriptionTemplateIncludesDefectSections() {
    PostTicketRQ postTicketRQ = new PostTicketRQ();
    postTicketRQ.setTestItemId(1L);
    postTicketRQ.setIncludeComments(false);
    postTicketRQ.setIncludeLogs(true);
    postTicketRQ.setIncludeScreenshots(true);
    postTicketRQ.setNumberOfLogs(1);

    HashMap<Long, String> backLinks = new HashMap<>();
    backLinks.put(3L, "https://localhost:8443/reportportal-ws/");
    postTicketRQ.setBackLinks(backLinks);

    TestItem rootItem = mock(TestItem.class);
    when(itemRepository.findById(1L)).thenReturn(Optional.of(rootItem));

    TestItem linkedItem = mock(TestItem.class);
    when(linkedItem.getLaunchId()).thenReturn(99L);
    when(linkedItem.getItemId()).thenReturn(3L);
    when(itemRepository.findById(3L)).thenReturn(Optional.of(linkedItem));

    Log log = mock(Log.class);
    when(log.getLogTime()).thenReturn(Instant.parse("2013-05-06T18:26:00Z"));
    doReturn(200).when(log).getLogLevel();
    when(log.getLogMessage()).thenReturn("Demo Test Log Message_spdOP");

    Attachment attachment = mock(Attachment.class);
    when(attachment.getContentType()).thenReturn("image/png");
    when(attachment.getFileId()).thenReturn("abcd");
    when(log.getAttachment()).thenReturn(attachment);

    when(logRepository.findAllUnderTestItemByLaunchIdAndTestItemIdsWithLimit(
        eq(99L), anyList(), eq(1)))
        .thenReturn(List.of(log));

    String description = descriptionService.getDescription(postTicketRQ);

    assertNotNull(description);
    assertTrue(description.contains("h3.*Back link to Report Portal:*"));
    assertTrue(description.contains("h3.*Steps:*"));
    assertTrue(description.contains("h3.*Actual Result:*"));
    assertTrue(description.contains("h3.*Expected Result:*"));
    assertTrue(description.contains("h3.*Attachment:*"));
    assertTrue(description.contains("!abcd.png|height=366!"));
  }

  @Test
  void testDescriptionTemplateIncludesPreconditionFromReport() {
    PostTicketRQ postTicketRQ = new PostTicketRQ();
    postTicketRQ.setTestItemId(1L);
    postTicketRQ.setIncludeComments(false);
    postTicketRQ.setIncludeLogs(false);
    postTicketRQ.setIncludeScreenshots(false);
    postTicketRQ.setNumberOfLogs(1);
    postTicketRQ.setBackLinks(new HashMap<>());

    TestItem rootItem = mock(TestItem.class, Mockito.RETURNS_DEEP_STUBS);
    when(rootItem.getItemResults().getIssue().getIssueDescription()).thenReturn(
        "Pre-condition from report");
    when(itemRepository.findById(1L)).thenReturn(Optional.of(rootItem));

    String description = descriptionService.getDescription(postTicketRQ);

    assertNotNull(description);
    assertTrue(description.contains("h3.*Pre-condition:*"));
    assertTrue(description.contains("Pre-condition from report"));
  }

  @Test
  void testDescriptionUsesReportLogsWithoutBackLinks() {
    PostTicketRQ postTicketRQ = new PostTicketRQ();
    postTicketRQ.setTestItemId(1L);
    postTicketRQ.setIncludeComments(false);
    postTicketRQ.setIncludeLogs(true);
    postTicketRQ.setIncludeScreenshots(false);
    postTicketRQ.setNumberOfLogs(1);
    postTicketRQ.setBackLinks(new HashMap<>());

    TestItem linkedItem = mock(TestItem.class);
    when(linkedItem.getLaunchId()).thenReturn(99L);
    when(linkedItem.getItemId()).thenReturn(1L);
    when(itemRepository.findById(1L)).thenReturn(Optional.of(linkedItem));

    Log log = mock(Log.class);
    when(log.getLogTime()).thenReturn(Instant.parse("2013-05-06T18:26:00Z"));
    doReturn(200).when(log).getLogLevel();
    when(log.getLogMessage()).thenReturn("Demo Test Log Message_spdOP");
    when(logRepository.findAllUnderTestItemByLaunchIdAndTestItemIdsWithLimit(
        eq(99L), anyList(), eq(1)))
        .thenReturn(List.of(log));

    String description = descriptionService.getDescription(postTicketRQ);

    assertNotNull(description);
    assertTrue(description.contains("h3.*Steps:*"));
    assertTrue(description.contains("h3.*Actual Result:*"));
    assertTrue(description.contains("h3.*Expected Result:*"));
  }
}
