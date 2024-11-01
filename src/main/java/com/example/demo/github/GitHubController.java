package com.example.demo.github;

import com.example.demo.chatpgt.ChatGptService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;
import java.util.*;

@RestController
@RequestMapping("/api")
public class GitHubController {

    @Autowired
    private ChatGptService chatGptService;

    @Autowired
    private RestTemplate restTemplate;

    @GetMapping("/generate-ai-test-cases")
    public ResponseEntity<String> generateAiTestCases(@RequestParam String owner, @RequestParam String repo, @RequestParam String accessToken) {
        Map<String, String> fileContents = fetchAllFilesFromRepo(owner, repo, accessToken);

        StringBuilder allTestCases = new StringBuilder();

        // Generate test cases for each file content and append the result
        for (Map.Entry<String, String> entry : fileContents.entrySet()) {
            String fileName = entry.getKey();
            String content = entry.getValue();

            String testCases = chatGptService.generateTestCases(fileName, content);

            allTestCases.append("File: ").append(fileName).append("\n")
                    .append(testCases).append("\n\n");
        }

        return ResponseEntity.ok(allTestCases.toString());
    }

    public Map<String, String> fetchAllFilesFromRepo(String owner, String repo, String accessToken) {
        return fetchFilesRecursively(owner, repo, "", accessToken);
    }

    private Map<String, String> fetchFilesRecursively(String owner, String repo, String path, String accessToken) {
        String apiUrl = String.format("https://api.github.com/repos/%s/%s/contents/%s", owner, repo, path);

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        headers.setAccept(Collections.singletonList(MediaType.APPLICATION_JSON));

        HttpEntity<String> entity = new HttpEntity<>(headers);
        ResponseEntity<List> response = restTemplate.exchange(apiUrl, HttpMethod.GET, entity, List.class);

        Map<String, String> fileContents = new HashMap<>();

        if (response.getStatusCode() == HttpStatus.OK) {
            List<Map<String, Object>> items = response.getBody();

            if (items != null) {
                for (Map<String, Object> item : items) {
                    String itemPath = (String) item.get("path");
                    String type = (String) item.get("type");

                    if ("file".equals(type)) {
                        // Fetch and decode file content
                        String content = fetchFileContentFromRepo(owner, repo, itemPath, accessToken);
                        fileContents.put(itemPath, content); // Store file path as key and content as value
                    } else if ("dir".equals(type)) {
                        // Recursively fetch files in this directory
                        fileContents.putAll(fetchFilesRecursively(owner, repo, itemPath, accessToken));
                    }
                }
            }
        } else {
            throw new RuntimeException("Failed to retrieve repository contents");
        }

        return fileContents;
    }

    public String fetchFileContentFromRepo(String owner, String repo, String path, String accessToken) {
        String apiUrl = String.format("https://api.github.com/repos/%s/%s/contents/%s", owner, repo, path);

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        headers.setAccept(Collections.singletonList(MediaType.APPLICATION_JSON));

        HttpEntity<String> entity = new HttpEntity<>(headers);

        ResponseEntity<Map> response = restTemplate.exchange(apiUrl, HttpMethod.GET, entity, Map.class);
        Map<String, Object> body = response.getBody();

        if (body != null && body.containsKey("content")) {
            String encodedContent = (String) body.get("content");
            // Decode from Base64 (GitHub returns file content in Base64 encoding)
            encodedContent = encodedContent.replaceAll("\\s+", "");

            byte[] decodedBytes = Base64.getDecoder().decode(encodedContent);
            return new String(decodedBytes, StandardCharsets.UTF_8);
        }

        throw new RuntimeException("Failed to retrieve file content");
    }

    // New endpoint to get user repositories
    @GetMapping("/user/repos")
    public ResponseEntity<List<Map<String, Object>>> getUserRepositories(@RequestParam("access_token") String accessToken) {
        String reposUrl = "https://api.github.com/user/repos";

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));

        HttpEntity<String> requestEntity = new HttpEntity<>(headers);

        ResponseEntity<List<Map<String, Object>>> response = restTemplate.exchange(
                reposUrl, HttpMethod.GET, requestEntity, new ParameterizedTypeReference<List<Map<String, Object>>>() {}
        );

        return response;
    }
}
