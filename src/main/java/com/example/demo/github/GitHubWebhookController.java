package com.example.demo.github;

import com.example.demo.chatpgt.ChatGptService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;

import java.util.Collections;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/webhook")
public class GitHubWebhookController {

    @Autowired
    private RestTemplate restTemplate;

    @Autowired
    private ChatGptService chatGptService;

    @Value("${access.token}")
    private String accessToken;

    @PostMapping
    public ResponseEntity<String> handleWebhook(
            @RequestBody Map<String, Object> payload,
            @RequestHeader("X-GitHub-Event") String eventType) {

        switch (eventType) {
            case "pull_request":
                return handlePullRequest(payload);
            default:
                return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                        .body("Unsupported event type: " + eventType);
        }
    }

    private ResponseEntity<String> handlePullRequest(Map<String, Object> payload) {
        String action = (String) payload.get("action");

        if ("opened".equals(action) || "synchronize".equals(action) || "edited".equals(action)) {
            Map<String, Object> pullRequest = (Map<String, Object>) payload.get("pull_request");
            String prUrl = (String) pullRequest.get("url");

            // Fetch changes in the pull request
            fetchPrChanges(prUrl);
            return ResponseEntity.ok("Pull request handled successfully.");
        }

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("Unsupported pull request action");
    }

    private void fetchPrChanges(String prUrl) {
        String apiUrl = prUrl + "/files";

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        headers.setAccept(Collections.singletonList(MediaType.APPLICATION_JSON));

        HttpEntity<String> entity = new HttpEntity<>(headers);

        ResponseEntity<List<Map<String, Object>>> response = restTemplate.exchange(apiUrl, HttpMethod.GET, entity, new ParameterizedTypeReference<List<Map<String, Object>>>() {});

        List<Map<String, Object>> changedFiles = response.getBody();

        // Process the changed files
        for (Map<String, Object> file : changedFiles) {
            String fileName = (String) file.get("filename");
            String patch = (String) file.get("patch");  // The "patch" contains the diff (code changes)

            // Process the code changes with ChatGPT
            processCodeChanges(fileName, patch);
        }
    }
    //TODO: this needs to be updated to use the correct generate test cases method based on app functionality (ex: contract tests, e2e tests)
    private void processCodeChanges(String fileName, String patch) {
        String updatedTestCases = chatGptService.generateTestCasesForChanges(fileName, patch);
    }
}
