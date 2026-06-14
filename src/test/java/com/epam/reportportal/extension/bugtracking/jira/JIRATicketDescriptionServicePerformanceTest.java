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

import static java.util.Collections.singletonList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.epam.reportportal.base.infrastructure.model.externalsystem.PostTicketRQ;
import com.epam.reportportal.base.infrastructure.persistence.dao.LogRepository;
import com.epam.reportportal.base.infrastructure.persistence.dao.TestItemRepository;
import com.epam.reportportal.base.infrastructure.persistence.entity.item.TestItem;
import com.epam.reportportal.base.infrastructure.persistence.entity.log.Log;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class JIRATicketDescriptionServicePerformanceTest {

  private final LogRepository logRepository = mock(LogRepository.class);
  private final TestItemRepository itemRepository = mock(TestItemRepository.class);
  private final JIRATicketDescriptionService descriptionService =
      new JIRATicketDescriptionService(logRepository, itemRepository);

  @Test
  void shouldLoadLogsOncePerBackLink() {
    long testItemId = 1L;
    long backLinkId = 2L;
    long launchId = 100L;
    long linkedItemId = 200L;

    TestItem rootItem = mock(TestItem.class);
    TestItem linkedItem = mock(TestItem.class);
    Log log = mock(Log.class);

    when(rootItem.getItemResults()).thenReturn(null);
    when(linkedItem.getLaunchId()).thenReturn(launchId);
    when(linkedItem.getItemId()).thenReturn(linkedItemId);
    when(linkedItem.getItemResults()).thenReturn(null);

    when(itemRepository.findById(testItemId)).thenReturn(Optional.of(rootItem));
    when(itemRepository.findById(backLinkId)).thenReturn(Optional.of(linkedItem));
    when(logRepository.findAllUnderTestItemByLaunchIdAndTestItemIdsWithLimit(launchId,
        singletonList(linkedItemId), 1)).thenReturn(List.of(log));
    when(log.getLogMessage()).thenReturn("Boom");
    when(log.getLogLevel()).thenReturn("ERROR");

    PostTicketRQ ticketRQ = new PostTicketRQ();
    ticketRQ.setTestItemId(testItemId);
    ticketRQ.setNumberOfLogs(1);
    ticketRQ.setIncludeLogs(true);
    ticketRQ.setIncludeScreenshots(false);
    HashMap<Long, String> backLinks = new HashMap<>();
    backLinks.put(backLinkId, "https://example.test/defect");
    ticketRQ.setBackLinks(backLinks);

    String description = descriptionService.getDescription(ticketRQ);

    verify(logRepository, times(1)).findAllUnderTestItemByLaunchIdAndTestItemIdsWithLimit(
        launchId, singletonList(linkedItemId), 1);
    verify(itemRepository, times(1)).findById(testItemId);
    verify(itemRepository, times(1)).findById(backLinkId);
    org.junit.jupiter.api.Assertions.assertTrue(description.contains("Boom"));
  }
}
