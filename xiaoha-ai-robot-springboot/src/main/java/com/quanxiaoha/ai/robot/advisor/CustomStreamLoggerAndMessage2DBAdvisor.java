package com.quanxiaoha.ai.robot.advisor;

import com.quanxiaoha.ai.robot.domain.dos.ChatMessageDO;
import com.quanxiaoha.ai.robot.domain.mapper.ChatMapper;
import com.quanxiaoha.ai.robot.domain.mapper.ChatMessageMapper;
import com.quanxiaoha.ai.robot.model.vo.chat.AiChatReqVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.transaction.support.TransactionTemplate;
import reactor.core.publisher.Flux;

import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicReference;

/**
 * @Author: 犬小哈
 * @Date: 2025/5/26 16:36
 * @Version: v1.0.0
 * @Description: 自定义打印流式日志 Advisor
 **/
@Slf4j
public class CustomStreamLoggerAndMessage2DBAdvisor implements StreamAdvisor {

    private final ChatMessageMapper chatMessageMapper;
    private final AiChatReqVO aiChatReqVO;
    private final TransactionTemplate transactionTemplate;

    public CustomStreamLoggerAndMessage2DBAdvisor(ChatMessageMapper chatMessageMapper,
                                                  AiChatReqVO aiChatReqVO,
                                                  TransactionTemplate transactionTemplate) {
        this.chatMessageMapper = chatMessageMapper;
        this.aiChatReqVO = aiChatReqVO;
        this.transactionTemplate = transactionTemplate;
    }

    @Override
    public int getOrder() {
        return 99; // order 值越小，越先执行
    }

    @Override
    public String getName() {
        return this.getClass().getSimpleName();
    }

    /**
     * 流式对话拦截方法（相当于流式调用的 AOP）
     *
     * 执行流程：
     *   1. 调用 streamAdvisorChain.nextStream() 触发后续链路（最终请求 AI 模型），得到响应流 Flux
     *   2. 通过 doOnNext    —— 每个流式 chunk 到达时，打印日志并拼接到 fullContent
     *   3. 通过 doOnComplete —— 流全部结束后，将用户消息和 AI 完整回答一并写入数据库
     *   4. 通过 doOnError   —— 出错时打印已收集的部分内容及异常信息
     *
     * @param chatClientRequest  封装了本次请求的消息、参数等上下文
     * @param streamAdvisorChain Advisor 责任链，调用 nextStream() 将请求传递给下一个 Advisor 或最终的 AI 模型
     * @return 处理后的响应流，每个元素为 AI 返回的一个 chunk 片段
     */
    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest chatClientRequest, StreamAdvisorChain streamAdvisorChain) {
        // 对话 UUID
        String chatUuid = aiChatReqVO.getChatId();
        // 用户消息
        String userMessage = aiChatReqVO.getMessage();

        // 调用责任链中的下一个处理器，最终触发 AI 模型，返回流式响应
        Flux<ChatClientResponse> chatClientResponseFlux = streamAdvisorChain.nextStream(chatClientRequest);

        // 用于跨异步线程聚合所有 chunk 的容器（AtomicReference 保证线程安全）
        AtomicReference<StringBuilder> fullContent = new AtomicReference<>(new StringBuilder());

        // 在原始响应流上挂载回调，实现日志打印和消息持久化，不改变流本身的数据
        return chatClientResponseFlux
                .doOnNext(response -> {
                    // 每收到一个 chunk 片段：打印日志，并追加到 fullContent 用于后续拼接完整回答
                    String chunk = response.chatResponse().getResult().getOutput().getText();

                    log.info("## chunk: {}", chunk);

                    // 若 chunk 块不为空，则追加到 fullContent 中
                    if (chunk != null) {
                        fullContent.get().append(chunk);
                    }
                })
                .doOnComplete(() -> {
                    // 流结束后，fullContent 中已聚合了 AI 的完整回答
                    String completeResponse = fullContent.get().toString();
                    log.info("\n==== FULL AI RESPONSE ====\n{}\n========================", completeResponse);

                    // 使用编程式事务，将用户消息和 AI 回答原子性地写入数据库，任一失败则全部回滚
                    transactionTemplate.execute(status -> {
                        try {
                            // 1. 存储用户消息
                            chatMessageMapper.insert(ChatMessageDO.builder()
                                    .chatUuid(chatUuid)
                                    .content(userMessage)
                                    .role(MessageType.USER.getValue()) // 用户消息
                                    .createTime(LocalDateTime.now())
                                    .build());

                            // 2. 存储 AI 回答
                            chatMessageMapper.insert(ChatMessageDO.builder()
                                    .chatUuid(chatUuid)
                                    .content(completeResponse)
                                    .role(MessageType.ASSISTANT.getValue()) // AI 回答
                                    .createTime(LocalDateTime.now())
                                    .build());

                            return true;
                        } catch (Exception ex) {
                            status.setRollbackOnly(); // 标记事务为回滚
                            log.error("", ex);
                        }
                        return false;
                    });
                })
                .doOnError(error -> {
                    // 流出错时，打印已收集的部分内容，便于排查问题
                    String partialResponse = fullContent.get().toString();
                    log.error("## Stream 流出现错误，已收集回答如下: {}", partialResponse, error);
                });
    }
}
