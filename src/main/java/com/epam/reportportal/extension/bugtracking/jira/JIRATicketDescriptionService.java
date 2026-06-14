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

import static com.epam.reportportal.base.infrastructure.persistence.commons.EntityUtils.INSTANT_TO_LDT;
import static java.util.Optional.ofNullable;

import com.epam.reportportal.base.infrastructure.model.externalsystem.PostTicketRQ;
import com.epam.reportportal.base.infrastructure.persistence.dao.LogRepository;
import com.epam.reportportal.base.infrastructure.persistence.dao.TestItemRepository;
import com.epam.reportportal.base.infrastructure.persistence.entity.attachment.Attachment;
import com.epam.reportportal.base.infrastructure.persistence.entity.item.TestItem;
import com.epam.reportportal.base.infrastructure.persistence.entity.log.Log;
import com.epam.reportportal.base.infrastructure.rules.exception.ErrorType;
import com.epam.reportportal.base.infrastructure.rules.exception.ReportPortalException;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.collections4.MapUtils;
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
  public static final String BACK_LINK_PATTERN = "[Link to defect|%s]%n";
  public static final String CODE = "{code}";
  private static final Logger LOGGER = LoggerFactory.getLogger(JIRATicketDescriptionService.class);
  private static final String DESCRIPTION_TITLE = "h2. Report Portal issue details\n\n";
  private static final String PRE_CONDITION_LABEL = "Pre-condition";
  private static final String STEPS_LABEL = "Steps";
  private static final String ACTUAL_RESULT_LABEL = "Actual Result";
  private static final String EXPECTED_RESULT_LABEL = "Expected Result";
  private static final String SCREENSHOT_LABEL = "Screenshot (If Have)";
  private static final String TESTCASE_ID_LABEL = "Testcase ID";
  private static final String IMAGE_CONTENT = "image";
  private static final String IMAGE_HEIGHT_TEMPLATE = "|height=366!";
  private static final String SECTION_SEPARATOR = "\n\n";
  private static final String BACK_LINK_SECTION_HEADER = "Back link to Report Portal";
  private static final String LOG_SECTION_HEADER = "h3. Test execution log\n";

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
    if (MapUtils.isEmpty(ticketRQ.getBackLinks())) {
      return "";
    }
    StringBuilder descriptionBuilder = new StringBuilder();
    descriptionBuilder.append(DESCRIPTION_TITLE);

    TestItem item = itemRepository.findById(ticketRQ.getTestItemId()).orElseThrow(
        () -> new ReportPortalException(ErrorType.TEST_ITEM_NOT_FOUND, ticketRQ.getTestItemId()));

    ticketRQ.getBackLinks().keySet().forEach(
        backLinkId -> updateDescriptionBuilder(descriptionBuilder, ticketRQ, backLinkId, item));

    return descriptionBuilder.toString();
  }

  private void updateDescriptionBuilder(StringBuilder descriptionBuilder, PostTicketRQ ticketRQ,
      Long backLinkId, TestItem item) {
    String backLink = ticketRQ.getBackLinks().get(backLinkId);
    Map<ReportSection, String> reportSections =
        extractReportSections(ofNullable(item.getItemResults()).flatMap(result -> ofNullable(
            result.getIssue())).map(issue -> issue.getIssueDescription()).orElse(""));
    List<Log> logs = loadLogs(backLinkId, ticketRQ);
    String testcaseId = StringUtils.defaultIfBlank(reportSections.get(ReportSection.TESTCASE_ID),
        String.valueOf(backLinkId));

    descriptionBuilder.append("* ").append(TESTCASE_ID_LABEL).append(": ")
        .append(testcaseId).append("\n");
    if (StringUtils.isNotBlank(backLink)) {
      descriptionBuilder.append("* ").append(BACK_LINK_SECTION_HEADER).append(": ")
          .append(String.format(BACK_LINK_PATTERN, backLink));
    }

    appendReportSection(descriptionBuilder, PRE_CONDITION_LABEL, reportSections.get(
        ReportSection.PRE_CONDITION));
    appendReportSection(descriptionBuilder, STEPS_LABEL, reportSections.get(ReportSection.STEPS));
    appendReportSection(descriptionBuilder, ACTUAL_RESULT_LABEL,
        StringUtils.defaultIfBlank(reportSections.get(ReportSection.ACTUAL_RESULT),
            extractActualResult(logs)));
    appendReportSection(descriptionBuilder, EXPECTED_RESULT_LABEL, "");

    if (ticketRQ.getIsIncludeScreenshots()) {
      appendScreenshots(descriptionBuilder, logs);
    }

    updateWithLogsInfo(descriptionBuilder, logs, ticketRQ);
  }

  private void appendScreenshots(StringBuilder descriptionBuilder, List<Log> logs) {
    if (CollectionUtils.isEmpty(logs)) {
      return;
    }
    boolean hasScreenshot = logs.stream().anyMatch(log -> ofNullable(log.getAttachment()).isPresent());
    if (!hasScreenshot) {
      return;
    }
    descriptionBuilder.append("* ").append(SCREENSHOT_LABEL).append(": ");
    logs.forEach(log -> ofNullable(log.getAttachment()).ifPresent(
        attachment -> addAttachment(descriptionBuilder, attachment)));
    descriptionBuilder.append("\n");
  }

  private List<Log> loadLogs(Long backLinkId, PostTicketRQ ticketRQ) {
    return itemRepository.findById(backLinkId)
        .flatMap(item -> ofNullable(item.getLaunchId()).map(launchId -> logRepository
            .findAllUnderTestItemByLaunchIdAndTestItemIdsWithLimit(launchId,
                Collections.singletonList(item.getItemId()), ticketRQ.getNumberOfLogs())))
        .orElse(Collections.emptyList());
  }

  private void appendReportSection(StringBuilder descriptionBuilder, String label, String value) {
    descriptionBuilder.append("* ").append(label).append(":");
    if (StringUtils.isNotBlank(value)) {
      descriptionBuilder.append(" ").append(value.trim());
    }
    descriptionBuilder.append("\n");
  }

  private Map<ReportSection, String> extractReportSections(String issueDescription) {
    Map<ReportSection, StringBuilder> sections = new LinkedHashMap<>();
    for (ReportSection section : ReportSection.values()) {
      sections.put(section, new StringBuilder());
    }
    if (StringUtils.isBlank(issueDescription)) {
      return toSectionMap(sections);
    }

    ReportSection currentSection = ReportSection.PRE_CONDITION;
    for (String rawLine : issueDescription.split("\\R")) {
      String line = rawLine.trim();
      ReportSection lineSection = resolveSection(line);
      if (lineSection != null) {
        currentSection = lineSection;
        String value = extractValue(line);
        if (StringUtils.isNotBlank(value)) {
          appendSectionValue(sections.get(currentSection), value);
        }
        continue;
      }
      if (StringUtils.isNotBlank(line)) {
        appendSectionValue(sections.get(currentSection), line);
      }
    }
    return toSectionMap(sections);
  }

  private Map<ReportSection, String> toSectionMap(Map<ReportSection, StringBuilder> sections) {
    Map<ReportSection, String> result = new LinkedHashMap<>();
    sections.forEach((section, value) -> result.put(section, StringUtils.trimToNull(value.toString())));
    return result;
  }

  private ReportSection resolveSection(String line) {
    String normalized = StringUtils.lowerCase(line, Locale.ROOT);
    if (normalized.startsWith("pre-condition:") || normalized.startsWith("precondition:")) {
      return ReportSection.PRE_CONDITION;
    }
    if (normalized.startsWith("steps:") || normalized.startsWith("step:")
        || normalized.startsWith("steps to reproduce:")) {
      return ReportSection.STEPS;
    }
    if (normalized.startsWith("actual result:") || normalized.startsWith("actual:")) {
      return ReportSection.ACTUAL_RESULT;
    }
    if (normalized.startsWith("expected result:") || normalized.startsWith("expected:")) {
      return ReportSection.EXPECTED_RESULT;
    }
    if (normalized.startsWith("testcase id:") || normalized.startsWith("test case id:")
        || normalized.startsWith("tc id:")) {
      return ReportSection.TESTCASE_ID;
    }
    return null;
  }

  private String extractValue(String line) {
    int colonIndex = line.indexOf(':');
    if (colonIndex < 0 || colonIndex == line.length() - 1) {
      return "";
    }
    return line.substring(colonIndex + 1).trim();
  }

  private void appendSectionValue(StringBuilder sectionBuilder, String value) {
    if (sectionBuilder.length() > 0) {
      sectionBuilder.append("\n");
    }
    sectionBuilder.append(value);
  }

  private String extractActualResult(List<Log> logs) {
    if (CollectionUtils.isEmpty(logs)) {
      return "";
    }
    return logs.stream()
        .filter(log -> StringUtils.isNotBlank(log.getLogLevel()))
        .filter(log -> "ERROR".equalsIgnoreCase(log.getLogLevel())
            || "FATAL".equalsIgnoreCase(log.getLogLevel()))
        .map(Log::getLogMessage)
        .filter(StringUtils::isNotBlank)
        .findFirst()
        .orElseGet(() -> logs.stream().map(Log::getLogMessage)
            .filter(StringUtils::isNotBlank)
            .findFirst().orElse(""));
  }

  private StringBuilder updateWithLogsInfo(StringBuilder descriptionBuilder, List<Log> logs,
      PostTicketRQ ticketRQ) {
    if (CollectionUtils.isNotEmpty(logs) && ticketRQ.getIsIncludeLogs()) {
      descriptionBuilder.append(SECTION_SEPARATOR).append(LOG_SECTION_HEADER).append(
          "{panel:title=Test execution log|borderStyle=solid|borderColor=#ccc|titleColor=#34302D|titleBGColor=#6DB33F}");
      logs.forEach(log -> updateWithLog(descriptionBuilder, log, true));
      descriptionBuilder.append("{panel}\n");
    }
    return descriptionBuilder;
  }

  private void updateWithLog(StringBuilder descriptionBuilder, Log log, boolean includeLog) {
    if (includeLog) {
      descriptionBuilder.append(CODE).append(getFormattedMessage(log)).append(CODE);
    }
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

  private enum ReportSection {
    PRE_CONDITION,
    STEPS,
    ACTUAL_RESULT,
    EXPECTED_RESULT,
    TESTCASE_ID
  }
}
