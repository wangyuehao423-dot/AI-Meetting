package com.example.interview.config;

import jakarta.annotation.PostConstruct;
import org.apache.pdfbox.pdmodel.font.FontInfo;
import org.apache.pdfbox.pdmodel.font.FontMapper;
import org.apache.pdfbox.pdmodel.font.FontMappers;
import org.apache.pdfbox.pdmodel.font.FontProvider;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;

@Component
public class PdfFontProviderConfig {

    // PDFBox 2.0.31 解析 PDF 时会扫描 C:\Windows\FONTS 下的系统字体；
    // 本机字体目录存在损坏的字体文件（如 mstmc.ttf），扫描过程中抛 EOFException。
    // 该异常发生在 PDType1Font 的静态初始化块里（外层表现为
    // ExceptionInInitializerError——是 Error 不是 Exception），业务代码
    // catch (Exception) 拦不住，导致任何 PDF 简历上传都直接 500。
    // 应用启动时用"空字体供应商"替换默认供应商：不再扫描系统字体。
    // PDF 内嵌字体与标准 14 字体（Helvetica/Times 等，自带字形度量）
    // 不受影响，文本抽取照常工作。
    @PostConstruct
    public void disableSystemFontScan() {
        FontMapper mapper = FontMappers.instance();
        try {
            // FontMapperImpl 是包私有类，但 setProvider 是它的公开方法：
            // 反射调用，把默认（会扫描系统字体的）供应商换成空供应商。
            Method setProvider = mapper.getClass()
                    .getMethod("setProvider", FontProvider.class);
            setProvider.setAccessible(true);
            setProvider.invoke(mapper, new FontProvider() {
                @Override
                public String toDebugString() {
                    return "no-op provider (system font scan disabled)";
                }

                @Override
                public List<? extends FontInfo> getFontInfo() {
                    return Collections.emptyList();
                }
            });
        } catch (ReflectiveOperationException | SecurityException e) {
            throw new IllegalStateException(
                    "无法禁用 PDFBox 系统字体扫描，简历解析将无法工作",
                    e
            );
        }
    }
}
