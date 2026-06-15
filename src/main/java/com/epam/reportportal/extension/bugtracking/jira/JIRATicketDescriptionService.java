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

import static com.epam.ta.reportportal.commons.EntityUtils.INSTANT_TO_LDT;
import static java.util.Optional.ofNullable;

import com.epam.reportportal.model.externalsystem.PostTicketRQ;
import com.epam.reportportal.rules.exception.ErrorType;
import com.epam.reportportal.rules.exception.ReportPortalException;
import com.epam.ta.reportportal.dao.LogRepository;
import com.epam.ta.reportportal.dao.TestItemRepository;
import com.epam.ta.reportportal.entity.attachment.Attachment;
import com.epam.ta.reportportal.entity.item.TestItem;
import com.epam.ta.reportportal.entity.log.Log;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.collections.MapUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.tika.config.TikaConfig;
import org.apache.tika.mime.MimeType;
import org.apache.tika.mime.MimeTypeException;
import org.apache.tika.mime.MimeTypes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Provide functionality for building jira's ticket description
 *
 * @author Aliaksei_Makayed
 * @author Dzmitry_Kavalets
 */
public class JIRATicketDescriptionService {
  public static final String JIRA_MARKUP_LINE_BREAK = "\\\\ ";
  public static final String BACK_LINK_HEADER = "h3.*Back link to Report Portal:*";
  public static final String BACK_LINK_PATTERN = "[Link to defect|%s]%n";
  public static final String PRE_CONDITION_HEADER = "h3.*Pre-condition:*";
  public static final String STEPS_HEADER = "h3.*Steps:*";
  public static final String ACTUAL_RESULT_HEADER = "h3.*Actual Result:*";
  public static final String EXPECTED_RESULT_HEADER = "h3.*Expected Result:*";
  public static final String ATTACHMENT_HEADER = "h3.*Attachment:*";
  public static final String CODE = "{code}";
  private static final String PANEL_TEMPLATE =
      "{panel:title=%s|borderStyle=solid|borderColor=#ccc|titleColor=#34302D|titleBGColor=#6DB33F}";
  private static final Logger LOGGER = LoggerFactory.getLogger(JIRATicketDescriptionService.class);
  private static final String IMAGE_CONTENT = "image";
  private static final String IMAGE_HEIGHT_TEMPLATE = "|height=366!";

  private final LogRepository logRepository;
  private final TestItemRepository itemRepository;
  private static final DateTimeFormatter JIRA_DATETIME_FORMATTER =
      DateTimeFormatter.ofPattern("MM/dd/yyyy HH:mm:ss").withZone(ZoneId.of("UTC"));
  private final MimeTypes mimeRepository;

  public JIRATicketDescriptionService(LogRepository logRepository,
      TestItemRepository itemRepository) {
    this.logRepository = logRepository;
    this.itemRepository = itemRepository;
    this.mimeRepository = TikaConfig.getDefaultConfig().getMimeRepository();
  }

  /**
   * Generate ticket description using logs of specified test item.
   *
   * @param ticketRQ
   * @return
   */
  public String getDescription(PostTicketRQ ticketRQ) {
    if (ticketRQ == null) {
      return "";
    }
    StringBuilder descriptionBuilder = new StringBuilder();
    Long testItemId = ticketRQ.getTestItemId();
    if (testItemId == null) {
      return "";
    }

    TestItem item = itemRepository.findById(testItemId).orElseThrow(
        () -> new ReportPortalException(ErrorType.TEST_ITEM_NOT_FOUND, testItemId));

    Map<Long, String> backLinks = ticketRQ.getBackLinks();
    if (MapUtils.isEmpty(backLinks)) {
      backLinks = new LinkedHashMap<>();
      backLinks.put(testItemId, "");
    }

    backLinks.forEach(
        (backLinkId, backLink) -> updateDescriptionBuilder(descriptionBuilder, ticketRQ, backLinkId,
            backLink, item));

    return descriptionBuilder.toString();
  }

  private void updateDescriptionBuilder(StringBuilder descriptionBuilder, PostTicketRQ ticketRQ,
      Long backLinkId, String backLink, TestItem item) {
    if (StringUtils.isNotBlank(backLink)) {
      descriptionBuilder.append(BACK_LINK_HEADER).append("\n").append(" - ")
          .append(String.format(BACK_LINK_PATTERN, backLink))
          .append("\n");
    }

    ofNullable(item.getItemResults()).flatMap(result -> ofNullable(result.getIssue()))
        .ifPresent(issue -> {
          if (StringUtils.isNotBlank(issue.getIssueDescription())) {
            descriptionBuilder.append(PRE_CONDITION_HEADER).append("\n")
                .append(issue.getIssueDescription()).append("\n");
          }
        });

    if (hasRequestedDescriptionContent(ticketRQ)) {
      List<Log> logs = getLogs(backLinkId, ticketRQ);
      if (CollectionUtils.isNotEmpty(logs)) {
        if (ticketRQ.getIsIncludeLogs()) {
          appendLogPanel(descriptionBuilder, STEPS_HEADER, "Steps", logs);
          appendLogPanel(descriptionBuilder, ACTUAL_RESULT_HEADER, "Actual Result",
              Collections.singletonList(logs.get(logs.size() - 1)));
        }
        appendExpectedResultSection(descriptionBuilder);
        if (ticketRQ.getIsIncludeScreenshots()) {
          appendAttachmentSection(descriptionBuilder, logs);
        }
      } else {
        appendExpectedResultSection(descriptionBuilder);
      }
    }
  }

  private List<Log> getLogs(Long backLinkId, PostTicketRQ ticketRQ) {
    return itemRepository.findById(backLinkId)
        .flatMap(item -> ofNullable(item.getLaunchId()).map(launchId -> logRepository
            .findAllUnderTestItemByLaunchIdAndTestItemIdsWithLimit(launchId,
                Collections.singletonList(item.getItemId()), ticketRQ.getNumberOfLogs())))
        .orElse(Collections.emptyList());
  }

  private void appendLogPanel(StringBuilder descriptionBuilder, String header, String panelTitle,
      List<Log> logs) {
    if (CollectionUtils.isEmpty(logs)) {
      return;
    }
    descriptionBuilder.append(header).append("\n")
        .append(String.format(PANEL_TEMPLATE, panelTitle));
    logs.forEach(log -> descriptionBuilder.append(CODE).append(getFormattedMessage(log))
        .append(CODE));
    descriptionBuilder.append("{panel}\n");
  }

  private void appendExpectedResultSection(StringBuilder descriptionBuilder) {
    descriptionBuilder.append(EXPECTED_RESULT_HEADER).append("\n\n");
  }

  private void appendAttachmentSection(StringBuilder descriptionBuilder, List<Log> logs) {
    descriptionBuilder.append(ATTACHMENT_HEADER).append("\n");
    logs.forEach(log -> ofNullable(log.getAttachment()).ifPresent(
        attachment -> addAttachment(descriptionBuilder, attachment)));
    descriptionBuilder.append("\n");
  }

  private boolean hasRequestedDescriptionContent(PostTicketRQ ticketRQ) {
    return Boolean.TRUE.equals(ticketRQ.getIsIncludeComments())
        || Boolean.TRUE.equals(ticketRQ.getIsIncludeLogs())
        || Boolean.TRUE.equals(ticketRQ.getIsIncludeScreenshots());
  }

  private String getFormattedMessage(Log log) {
    StringBuilder messageBuilder = new StringBuilder();
    ofNullable(log.getLogTime())
        .ifPresent(logTime -> messageBuilder.append(" Time: ")
            .append(INSTANT_TO_LDT.apply(logTime).format(JIRA_DATETIME_FORMATTER))
            .append(", "));
    ofNullable(log.getLogLevel()).ifPresent(
        logLevel -> messageBuilder.append("Level: ").append(logLevel).append(", "));
    messageBuilder.append("Log: ").append(log.getLogMessage()).append("\n");
    return messageBuilder.toString();
  }

  private void addAttachment(StringBuilder descriptionBuilder, Attachment attachment) {
    if (StringUtils.isNotBlank(attachment.getContentType()) && StringUtils.isNotBlank(
        attachment.getFileId())) {
      try {
        MimeType mimeType = mimeRepository.forName(attachment.getContentType());
        if (attachment.getContentType().contains(IMAGE_CONTENT)) {
          descriptionBuilder.append("!").append(attachment.getFileId())
              .append(mimeType.getExtension()).append(IMAGE_HEIGHT_TEMPLATE);
        } else {
          descriptionBuilder.append("[^").append(attachment.getFileId())
              .append(mimeType.getExtension()).append("]");
        }
        descriptionBuilder.append(JIRA_MARKUP_LINE_BREAK);
      } catch (MimeTypeException e) {
        descriptionBuilder.append(JIRA_MARKUP_LINE_BREAK);
        LOGGER.error("JIRATicketDescriptionService error: " + e.getMessage(), e);
      }

    }
  }
}
