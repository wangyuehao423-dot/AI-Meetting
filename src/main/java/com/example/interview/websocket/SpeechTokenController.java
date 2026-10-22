package com.example.interview.websocket;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController 
@RequestMapping("/api/speech")
public class SpeechTokenController {
    private final AliyunNlsTokenService tokenService;
    public SpeechTokenController(AliyunNlsTokenService tokenService) {
        this.tokenService = tokenService;
    }

    @GetMapping("/token")
    public Map<String,String> token()
    {
        return Map.of(
                "token", tokenService.getToken(),
                "appKey", tokenService.getAppKey()
        );
    }
}
