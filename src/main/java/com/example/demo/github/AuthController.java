package com.example.demo.github;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Controller;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.servlet.view.RedirectView;

import java.util.Collections;
import java.util.List;
import java.util.Map;

@Controller
public class AuthController {

    @Value("${github.client.id}")
    private String clientId;

    @Value("${github.client.secret}")
    private String clientSecret;

    @Value("${oauth.callback.url}")
    private String callbackUrl;

    @GetMapping("/auth/github")
    public RedirectView githubLogin() {
        String githubLoginUrl = "https://github.com/login/oauth/authorize?client_id=" + clientId +
                "&redirect_uri=" + callbackUrl +
                "&scope=repo,user";
        return new RedirectView(githubLoginUrl);
    }

    @GetMapping("/auth/github/callback")
    public ResponseEntity<List<String>> githubCallback(@RequestParam("code") String code) {
        String tokenUrl = "https://github.com/login/oauth/access_token";
        String userUrl = "https://api.github.com/user";

        // Create a RestTemplate instance
        RestTemplate restTemplate = new RestTemplate();

        // Create headers and set client ID and secret
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        headers.setAccept(Collections.singletonList(MediaType.APPLICATION_JSON));

        MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
        body.add("client_id", clientId);
        body.add("client_secret", clientSecret); // Use the injected client secret
        body.add("code", code);

        HttpEntity<MultiValueMap<String, String>> requestEntity = new HttpEntity<>(body, headers);

        // Exchange authorization code for access token
        ResponseEntity<Map> response = restTemplate.exchange(tokenUrl, HttpMethod.POST, requestEntity, Map.class);
        String accessToken = (String) response.getBody().get("access_token");

        if (accessToken != null) {
            HttpHeaders userHeaders = new HttpHeaders();
            userHeaders.setBearerAuth(accessToken);
            userHeaders.setAccept(Collections.singletonList(MediaType.APPLICATION_JSON));

            HttpEntity<String> userRequest = new HttpEntity<>(userHeaders);

            ResponseEntity<Map> userResponse = restTemplate.exchange(userUrl, HttpMethod.GET, userRequest, Map.class);
            String userId = String.valueOf(userResponse.getBody().get("login"));
            List<String> result = List.of("access_token=" + accessToken, "user_id=" + userId);
            return ResponseEntity.ok(result);
        }

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Collections.singletonList("Failed to retrieve access token"));
    }

}
