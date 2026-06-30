package com.hao.universalassistantbackend.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hao.universalassistantbackend.entity.ConversationEntity;
import com.hao.universalassistantbackend.entity.MessageEntity;
import com.hao.universalassistantbackend.entity.ConversationSummaryEntity;
import com.hao.universalassistantbackend.model.AgentRunContext;
import com.hao.universalassistantbackend.model.ChatMessage;
import com.hao.universalassistantbackend.model.ConversationMessageResponse;
import com.hao.universalassistantbackend.model.ConversationMessagesResponse;
import com.hao.universalassistantbackend.model.ConversationResponse;
import com.hao.universalassistantbackend.model.SearchResult;
import com.hao.universalassistantbackend.repository.ConversationRepository;
import com.hao.universalassistantbackend.repository.MessageRepository;
import com.hao.universalassistantbackend.repository.ConversationSummaryRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
public class ConversationService {

    private static final String DEFAULT_TITLE = "新会话";
    private static final int TITLE_MAX_LENGTH = 40;
    private static final TypeReference<List<SearchResult>> SEARCH_RESULT_LIST = new TypeReference<>() {
    };

    private record DisplayMessage(Instant createdAt, ConversationMessageResponse response) {
    }

    private final ConversationRepository conversationRepository;
    private final MessageRepository messageRepository;
    private final ConversationSummaryRepository conversationSummaryRepository;
    private final AgentRunService agentRunService;
    private final MemoryService memoryService;
    private final ObjectMapper objectMapper;

    public ConversationService(ConversationRepository conversationRepository,
                               MessageRepository messageRepository,
                               ConversationSummaryRepository conversationSummaryRepository,
                               AgentRunService agentRunService,
                               MemoryService memoryService,
                               ObjectMapper objectMapper) {
        this.conversationRepository = conversationRepository;
        this.messageRepository = messageRepository;
        this.conversationSummaryRepository = conversationSummaryRepository;
        this.agentRunService = agentRunService;
        this.memoryService = memoryService;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public List<ConversationResponse> listConversations() {
        return conversationRepository.findTop50ByArchivedFalseOrderByUpdatedAtDesc()
                .stream()
                .map(this::toConversationResponse)
                .toList();
    }

    @Transactional
    public ConversationResponse createConversation(String title) {
        ConversationEntity conversation = new ConversationEntity();
        conversation.setTitle(StringUtils.hasText(title) ? title.trim() : DEFAULT_TITLE);
        conversation.setArchived(false);
        return toConversationResponse(conversationRepository.save(conversation));
    }

    @Transactional
    public ConversationResponse renameConversation(String conversationId, String title) {
        UUID id = parseConversationId(conversationId).orElseThrow();
        ConversationEntity conversation = conversationRepository.findById(id).orElseThrow();
        conversation.setTitle(StringUtils.hasText(title) ? title.trim() : DEFAULT_TITLE);
        conversation.touch();
        return toConversationResponse(conversationRepository.save(conversation));
    }

    @Transactional
    public ConversationResponse archiveConversation(String conversationId) {
        UUID id = parseConversationId(conversationId).orElseThrow();
        ConversationEntity conversation = conversationRepository.findById(id).orElseThrow();
        conversation.setArchived(true);
        conversation.touch();
        return toConversationResponse(conversationRepository.save(conversation));
    }

    @Transactional(readOnly = true)
    public ConversationMessagesResponse getMessages(String conversationId) {
        UUID id = parseConversationId(conversationId).orElseThrow();
        List<MessageEntity> activeMessages = messageRepository.findByConversation_IdAndInvalidatedAtIsNullOrderByCreatedAtAsc(id);
        List<UUID> assistantMessageIds = activeMessages.stream()
                .filter(message -> "assistant".equals(message.getRole()))
                .map(MessageEntity::getId)
                .toList();
        Map<UUID, AgentRunContext> contexts = agentRunService.contextsByAssistantMessageIds(assistantMessageIds);
        List<DisplayMessage> displayMessages = new ArrayList<>();
        activeMessages.stream()
                .filter(message -> !isLegacyEmptyAssistantPlaceholder(message, contexts.get(message.getId())))
                .map(message -> new DisplayMessage(message.getCreatedAt(), toMessageResponse(message, contexts.get(message.getId()))))
                .forEach(displayMessages::add);

        agentRunService.unresolvedContexts(id).stream()
                .map(context -> new DisplayMessage(context.run().getCreatedAt(), toRunMessageResponse(context)))
                .forEach(displayMessages::add);

        List<ConversationMessageResponse> messages = displayMessages.stream()
                .sorted((left, right) -> left.createdAt().compareTo(right.createdAt()))
                .map(DisplayMessage::response)
                .toList();
        return new ConversationMessagesResponse(id, messages);
    }

    @Transactional
    public ConversationEntity getOrCreateConversation(String conversationId, String firstUserMessage) {
        Optional<UUID> id = parseConversationId(conversationId);
        if (id.isPresent()) {
            Optional<ConversationEntity> existing = conversationRepository.findById(id.get());
            if (existing.isPresent()) {
                return existing.get();
            }
        }

        ConversationEntity conversation = new ConversationEntity();
        conversation.setTitle(generateTitle(firstUserMessage));
        conversation.setArchived(false);
        return conversationRepository.save(conversation);
    }

    @Transactional(readOnly = true)
    public List<ChatMessage> recentHistory(UUID conversationId, int limit) {
        List<MessageEntity> newest = messageRepository.findTop20ByConversation_IdAndInvalidatedAtIsNullOrderByCreatedAtDesc(conversationId);
        Collections.reverse(newest);

        int fromIndex = Math.max(0, newest.size() - Math.max(1, limit));
        return newest.subList(fromIndex, newest.size())
                .stream()
                .map(message -> new ChatMessage(message.getRole(), message.getContent()))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<ChatMessage> recentHistoryBefore(UUID conversationId, Instant createdAt, int limit) {
        List<MessageEntity> newest = messageRepository.findTop20ByConversation_IdAndInvalidatedAtIsNullAndCreatedAtBeforeOrderByCreatedAtDesc(conversationId, createdAt);
        Collections.reverse(newest);

        int fromIndex = Math.max(0, newest.size() - Math.max(1, limit));
        return newest.subList(fromIndex, newest.size())
                .stream()
                .map(message -> new ChatMessage(message.getRole(), message.getContent()))
                .toList();
    }

    @Transactional(readOnly = true)
    public Optional<String> summaryText(UUID conversationId) {
        return conversationSummaryRepository.findByConversation_Id(conversationId)
                .map(ConversationSummaryEntity::getSummary)
                .filter(StringUtils::hasText);
    }

    @Transactional(readOnly = true)
    public boolean shouldRefreshSummary(UUID conversationId, int minMessages, int refreshInterval) {
        long messageCount = messageRepository.countByConversation_IdAndInvalidatedAtIsNull(conversationId);
        if (messageCount < minMessages) {
            return false;
        }

        return conversationSummaryRepository.findByConversation_Id(conversationId)
                .map(summary -> messageCount - summary.getMessageCount() >= refreshInterval)
                .orElse(true);
    }

    @Transactional(readOnly = true)
    public List<ChatMessage> messagesForSummary(UUID conversationId, int limit) {
        List<MessageEntity> newest = messageRepository.findTop20ByConversation_IdAndInvalidatedAtIsNullOrderByCreatedAtDesc(conversationId);
        Collections.reverse(newest);

        int fromIndex = Math.max(0, newest.size() - Math.max(1, limit));
        return newest.subList(fromIndex, newest.size())
                .stream()
                .map(message -> new ChatMessage(message.getRole(), message.getContent()))
                .toList();
    }

    @Transactional
    public void upsertSummary(ConversationEntity conversation, String summary) {
        if (!StringUtils.hasText(summary)) {
            return;
        }

        long messageCount = messageRepository.countByConversation_IdAndInvalidatedAtIsNull(conversation.getId());
        ConversationSummaryEntity entity = conversationSummaryRepository.findByConversation_Id(conversation.getId())
                .orElseGet(() -> {
                    ConversationSummaryEntity next = new ConversationSummaryEntity();
                    next.setConversation(conversation);
                    return next;
                });
        entity.setSummary(summary.trim());
        entity.setMessageCount((int) Math.min(Integer.MAX_VALUE, messageCount));
        conversationSummaryRepository.save(entity);
    }

    @Transactional
    public MessageEntity saveUserMessage(ConversationEntity conversation, String content) {
        ensureConversationTitle(conversation, content);
        return saveMessage(conversation, "user", content, null, null, null, List.of());
    }

    @Transactional
    public MessageEntity saveAssistantMessage(ConversationEntity conversation,
                                              String content,
                                              String model,
                                              boolean realtimeSearchUsed,
                                              boolean modelAvailable,
                                              List<SearchResult> sources) {
        return saveMessage(conversation, "assistant", content, model, realtimeSearchUsed, modelAvailable, sources, "completed");
    }

    @Transactional
    public MessageEntity saveAssistantMessage(ConversationEntity conversation,
                                              String content,
                                              String model,
                                              boolean realtimeSearchUsed,
                                              boolean modelAvailable,
                                              List<SearchResult> sources,
                                              String status) {
        return saveMessage(conversation, "assistant", content, model, realtimeSearchUsed, modelAvailable, sources, status);
    }

    @Transactional
    public MessageEntity editUserMessageAndInvalidateAfter(String conversationId, String messageId, String content) {
        UUID conversationUuid = parseConversationId(conversationId).orElseThrow();
        UUID messageUuid = parseConversationId(messageId).orElseThrow();
        MessageEntity message = messageRepository.findById(messageUuid).orElseThrow();
        if (!conversationUuid.equals(message.getConversation().getId()) || !"user".equals(message.getRole())) {
            throw new IllegalArgumentException("只能编辑当前会话中的用户消息。");
        }

        Instant now = Instant.now();
        message.setContent(content == null ? "" : content.trim());
        message.setRevision(message.getRevision() + 1);
        message.setEditedAt(now);
        messageRepository.save(message);

        messageRepository.invalidateAfter(conversationUuid, message.getCreatedAt(), now);
        agentRunService.invalidateAfterOrForUserMessage(conversationUuid, messageUuid, message.getCreatedAt());
        memoryService.invalidateAfter(conversationUuid, message.getCreatedAt());
        conversationSummaryRepository.deleteByConversation_Id(conversationUuid);

        ConversationEntity conversation = message.getConversation();
        conversation.touch();
        conversationRepository.save(conversation);
        return message;
    }

    private MessageEntity saveMessage(ConversationEntity conversation,
                                      String role,
                                      String content,
                                      String model,
                                      Boolean realtimeSearchUsed,
                                      Boolean modelAvailable,
                                      List<SearchResult> sources) {
        return saveMessage(conversation, role, content, model, realtimeSearchUsed, modelAvailable, sources, "completed");
    }

    private MessageEntity saveMessage(ConversationEntity conversation,
                                      String role,
                                      String content,
                                      String model,
                                      Boolean realtimeSearchUsed,
                                      Boolean modelAvailable,
                                      List<SearchResult> sources,
                                      String status) {
        MessageEntity message = new MessageEntity();
        message.setConversation(conversation);
        message.setRole(role);
        message.setContent(content == null ? "" : content);
        message.setModel(model);
        message.setRealtimeSearchUsed(realtimeSearchUsed);
        message.setModelAvailable(modelAvailable);
        message.setSourcesJson(writeSources(sources));
        message.setStatus(StringUtils.hasText(status) ? status : "completed");

        conversation.touch();
        conversationRepository.save(conversation);
        return messageRepository.save(message);
    }

    private void ensureConversationTitle(ConversationEntity conversation, String firstUserMessage) {
        long messageCount = messageRepository.countByConversation_IdAndInvalidatedAtIsNull(conversation.getId());
        if (messageCount == 0 && (!StringUtils.hasText(conversation.getTitle()) || DEFAULT_TITLE.equals(conversation.getTitle()))) {
            conversation.setTitle(generateTitle(firstUserMessage));
        }
    }

    private ConversationResponse toConversationResponse(ConversationEntity conversation) {
        return new ConversationResponse(
                conversation.getId(),
                conversation.getTitle(),
                conversation.getCreatedAt(),
                conversation.getUpdatedAt()
        );
    }

    private ConversationMessageResponse toMessageResponse(MessageEntity message, AgentRunContext agentRunContext) {
        String content = message.getContent();
        if ("assistant".equals(message.getRole()) && !StringUtils.hasText(content) && agentRunContext != null) {
            content = recoverRunAnswer(agentRunContext);
        }
        String status = message.getStatus();
        if ("assistant".equals(message.getRole()) && agentRunContext != null && !"completed".equals(status)) {
            status = agentRunContext.run().getStatus();
        }
        Boolean realtimeSearchUsed = message.getRealtimeSearchUsed();
        Boolean modelAvailable = message.getModelAvailable();
        List<SearchResult> sources = readSources(message.getSourcesJson());
        if ("assistant".equals(message.getRole()) && agentRunContext != null) {
            realtimeSearchUsed = realtimeSearchUsed == null ? agentRunContext.run().getRealtimeSearchUsed() : realtimeSearchUsed;
            modelAvailable = modelAvailable == null ? agentRunContext.run().getModelAvailable() : modelAvailable;
            if (sources.isEmpty()) {
                sources = readSources(agentRunContext.run().getSourcesJson());
            }
        }
        return new ConversationMessageResponse(
                message.getId(),
                message.getRole(),
                content,
                message.getModel(),
                realtimeSearchUsed,
                modelAvailable,
                sources,
                status,
                message.getRevision(),
                message.getEditedAt(),
                agentRunContext == null ? null : agentRunContext.run().getId(),
                agentRunContext == null ? List.of() : agentRunContext.steps(),
                null,
                null,
                null,
                message.getCreatedAt()
        );
    }

    private ConversationMessageResponse toRunMessageResponse(AgentRunContext agentRunContext) {
        Map<String, Object> pendingInput = agentRunService.readPendingToolInput(agentRunContext.run());
        String pendingToolInput = stringValue(pendingInput.get("query"));
        String pendingToolReason = stringValue(pendingInput.get("reason"));
        String content = recoverRunAnswer(agentRunContext);
        return new ConversationMessageResponse(
                agentRunContext.run().getId(),
                "assistant",
                content,
                agentRunContext.run().getModel(),
                agentRunContext.run().getRealtimeSearchUsed(),
                agentRunContext.run().getModelAvailable(),
                readSources(agentRunContext.run().getSourcesJson()),
                agentRunContext.run().getStatus(),
                1,
                null,
                agentRunContext.run().getId(),
                agentRunContext.steps(),
                agentRunContext.run().getPendingToolName(),
                pendingToolInput,
                pendingToolReason,
                agentRunContext.run().getCreatedAt()
        );
    }

    private boolean isLegacyEmptyAssistantPlaceholder(MessageEntity message, AgentRunContext agentRunContext) {
        return "assistant".equals(message.getRole())
                && !StringUtils.hasText(message.getContent())
                && "streaming".equals(message.getStatus())
                && (agentRunContext == null || !StringUtils.hasText(recoverRunAnswer(agentRunContext)));
    }

    private String recoverRunAnswer(AgentRunContext agentRunContext) {
        if (agentRunContext == null) {
            return "";
        }
        if (StringUtils.hasText(agentRunContext.run().getFinalAnswer())) {
            return agentRunContext.run().getFinalAnswer();
        }
        if (StringUtils.hasText(agentRunContext.run().getPartialAnswer())) {
            return agentRunContext.run().getPartialAnswer();
        }
        if (StringUtils.hasText(agentRunContext.run().getErrorMessage())) {
            return agentRunContext.run().getErrorMessage();
        }
        if ("cancelled".equals(agentRunContext.run().getStatus())) {
            return "回答已中断。你可以编辑上一条问题后重新发送。";
        }
        return "";
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private Optional<UUID> parseConversationId(String conversationId) {
        if (!StringUtils.hasText(conversationId)) {
            return Optional.empty();
        }

        try {
            return Optional.of(UUID.fromString(conversationId.trim()));
        } catch (IllegalArgumentException ignored) {
            return Optional.empty();
        }
    }

    private String generateTitle(String message) {
        if (!StringUtils.hasText(message)) {
            return DEFAULT_TITLE;
        }

        String title = message.replaceAll("\\s+", " ").trim();
        if (title.length() > TITLE_MAX_LENGTH) {
            return title.substring(0, TITLE_MAX_LENGTH - 1) + "…";
        }

        return title;
    }

    private String writeSources(List<SearchResult> sources) {
        if (sources == null || sources.isEmpty()) {
            return null;
        }

        try {
            return objectMapper.writeValueAsString(sources);
        } catch (RuntimeException ex) {
            return null;
        } catch (Exception ex) {
            return null;
        }
    }

    private List<SearchResult> readSources(String sourcesJson) {
        if (!StringUtils.hasText(sourcesJson)) {
            return List.of();
        }

        try {
            return objectMapper.readValue(sourcesJson, SEARCH_RESULT_LIST);
        } catch (Exception ex) {
            return new ArrayList<>();
        }
    }
}
