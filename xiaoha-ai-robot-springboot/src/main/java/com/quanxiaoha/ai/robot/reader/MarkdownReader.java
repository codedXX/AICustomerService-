package com.quanxiaoha.ai.robot.reader;

import cn.hutool.core.collection.CollUtil;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.markdown.MarkdownDocumentReader;
import org.springframework.ai.reader.markdown.config.MarkdownDocumentReaderConfig;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * @Author: 犬小哈
 * @Date: 2025/7/21 16:09
 * @Version: v1.0.0
 * @Description: Markdown 文件读取
 **/
@Component
public class MarkdownReader {


    /**
     *  为什么要切割？
     *  一个 Markdown 文件可能很长，比如：
     * # 退款政策
     * 如果您对购买的产品不满意，可以在7天内申请退款。
     * ---
     * # 发货时间
     * 我们会在付款后24小时内发货。
     * ---
     * # 联系客服
     * 请拨打 400-xxx-xxxx。
     *
     * 如果整个文件作为一个整体向量化，用户问"发货多久"时，AI 要在整篇文章里找答案，干扰信息多，准确度低。
     * 切成小块后，每块只有一个主题，搜索时能精准命中
     */
    /**
     * 读取 Markdown 文件为文档集合
     * @param resource
     * @param metadatas
     * @return
     */
    public List<Document> loadMarkdown(Resource resource, Map<String, Object> metadatas) {
        // MarkdownDocumentReader 阅读器配置类
        //配置切割规则
        MarkdownDocumentReaderConfig.Builder configBuilder = MarkdownDocumentReaderConfig.builder()
                .withHorizontalRuleCreateDocument(true) // 遇到水平线 ---，则创建新文档
                .withIncludeCodeBlock(false) // 排除代码块（代码块生成单独文档）
                .withIncludeBlockquote(false); // 排除块引用（块引用生成单独文档）

        // 添加自定义元数据，如文件名称
        // 给每个 Document 打上元数据标签
        if (CollUtil.isNotEmpty(metadatas)) {
            configBuilder.withAdditionalMetadata(metadatas);
        }

        // 新建 MarkdownDocumentReader 阅读器
        //只是创建对象，告诉它"你要读哪个文件、按什么规则切"，文件还没动
        MarkdownDocumentReader reader = new MarkdownDocumentReader(resource, configBuilder.build());

        // 读取并转换为 Document 文档集合
        //// ↑ 这行才真正打开文件、按规则切割、返回 List<Document>
        return reader.get();

        /**
         * 切割后结果示意：
         * 原始文件（1个）
         *      │
         *      ▼ loadMarkdown
         * [Document1]  内容："如果您对购买的产品不满意，可以在7天内申请退款。"
         *              元数据：{mdStorageId: 1, originalFileName: "FAQ.md"}
         *
         * [Document2]  内容："我们会在付款后24小时内发货。"
         *              元数据：{mdStorageId: 1, originalFileName: "FAQ.md"}
         *
         * [Document3]  内容："请拨打 400-xxx-xxxx。"
         *              元数据：{mdStorageId: 1, originalFileName: "FAQ.md"}
         */
    }
}
