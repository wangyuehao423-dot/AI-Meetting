package com.example.interview.application;

import org.apache.tika.Tika;
import org.springframework.security.core.parameters.P;
import org.springframework.stereotype.Service;

import java.nio.file.Path;

@Service
public class ResumeParserService
{
    private final Tika tika = new Tika();
    public String parse(Path path)
    {
        try {
            var text = tika.parseToString(path);
            if (text == null || text.isBlank()) {
                throw new IllegalArgumentException(
                        "resume contains no readable text"
                );
            }
            return text.trim();
        }
        catch (Exception e)
        {
            throw new IllegalArgumentException(
                    "简历解析失败",
                    e
            );
        }
    }
}
