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
import java.util.stream.Collectors;

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
    public ResponseEntity<List<String>> getUserRepositoryNames(@RequestParam("access_token") String accessToken) {
        String reposUrl = "https://api.github.com/user/repos";

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));

        HttpEntity<String> requestEntity = new HttpEntity<>(headers);

        ResponseEntity<List<Map<String, Object>>> response = restTemplate.exchange(
                reposUrl, HttpMethod.GET, requestEntity, new ParameterizedTypeReference<List<Map<String, Object>>>() {}
        );

        // Extract just the repository names
        List<String> repoNames = response.getBody().stream()
                .map(repo -> (String) repo.get("name"))
                .collect(Collectors.toList());

        return ResponseEntity.ok(repoNames);
    }

    @GetMapping("/user/repo/prs")
    public ResponseEntity<List<String>> getActivePullRequests(
            @RequestParam("user") String user,
            @RequestParam("access_token") String accessToken,
            @RequestParam("repo_name") String repoName) {

        String prsUrl = "https://api.github.com/repos/{user}/{repo}/pulls";

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));

        HttpEntity<String> requestEntity = new HttpEntity<>(headers);

        String formattedUrl = prsUrl.replace("{user}", user).replace("{repo}", repoName);

        ResponseEntity<List<Map<String, Object>>> response = restTemplate.exchange(
                formattedUrl, HttpMethod.GET, requestEntity, new ParameterizedTypeReference<List<Map<String, Object>>>() {}
        );

        // Extract PR titles from active PRs
        List<String> prNames = response.getBody().stream()
                .filter(pr -> "open".equals(pr.get("state")))
                .map(pr -> (String) pr.get("title"))
                .collect(Collectors.toList());

        return ResponseEntity.ok(prNames);
    }

    @GetMapping("/generate-contract-test-cases")
    public ResponseEntity<String> generateContractTestCases(@RequestParam String user, @RequestParam String repo_name, @RequestParam String access_token) {
        // Fetch all files from the repository
        Map<String, String> fileContents = fetchAllFilesFromRepo(user, repo_name, access_token);

        StringBuilder allContractTestCases = new StringBuilder();

        // Generate contract test cases for each Swagger file found
        for (Map.Entry<String, String> entry : fileContents.entrySet()) {
            String fileName = entry.getKey();
            String content = entry.getValue();

            // Check if the file is a Swagger file (could be identified by filename, e.g., swagger.json or openapi.yaml)
            if (fileName.toLowerCase().contains("swagger") || fileName.toLowerCase().contains("openapi")) {
                String contractTestCases = chatGptService.generateInitialContractTestCases(fileName, content);

                allContractTestCases.append("File: ").append(fileName).append("\n")
                        .append(contractTestCases).append("\n\n");
            }
        }

        // Return the contract test cases generated for Swagger files
        if (allContractTestCases.isEmpty()) {
            return ResponseEntity.ok("No Swagger files found for generating contract test cases.");
        } else {
            return ResponseEntity.ok(allContractTestCases.toString());
        }
    }

    @GetMapping("/update-contract-test-cases")
    public ResponseEntity<List<String>> updateContractTestCases(
            @RequestParam String user,
            @RequestParam String repo_name,
            @RequestParam String access_token,
            @RequestParam String pr_name) {

        // Step 1: Retrieve the list of open PRs for the repository
        List<Map<String, Object>> openPRs = fetchOpenPullRequests(user, repo_name, access_token);

        // Step 2: Find the PR by name
        Map<String, Object> targetPR = openPRs.stream()
                .filter(pr -> pr_name.equals(pr.get("title")))
                .findFirst()
                .orElseThrow(() -> new RuntimeException("PR not found"));

        System.out.println(targetPR.get("number"));

        // Step 3: Retrieve the code changes for the specified PR
        String prCodeChanges = getPRCodeChanges(user, repo_name, access_token, (Integer) targetPR.get("number"));

        // Step 4: Identify Swagger file name (assuming it's in the code changes or repository)
        String swaggerFileName = findSwaggerFileName(prCodeChanges); // Custom method to locate Swagger file
        System.out.println(swaggerFileName);
        // Step 5: Call the OpenAI service method to get updated contract test cases
        List<String> updatedTestCases = chatGptService.updateContractTestCases(prCodeChanges, swaggerFileName);
        // Step 6: Return the list of updated test cases
        return ResponseEntity.ok(updatedTestCases);
    }

    private List<Map<String, Object>> fetchOpenPullRequests(String user, String repo, String accessToken) {
        String url = "https://api.github.com/repos/" + user + "/" + repo + "/pulls?state=open";

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));

        HttpEntity<String> requestEntity = new HttpEntity<>(headers);

        ResponseEntity<List<Map<String, Object>>> response = restTemplate.exchange(
                url, HttpMethod.GET, requestEntity, new ParameterizedTypeReference<List<Map<String, Object>>>() {}
        );

        return response.getBody() != null ? response.getBody() : new ArrayList<>();
    }

    private String getPRCodeChanges(String user, String repo, String accessToken, int prNumber) {
        String url = "https://api.github.com/repos/" + user + "/" + repo + "/pulls/" + prNumber + "/files";

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));

        HttpEntity<String> requestEntity = new HttpEntity<>(headers);

        ResponseEntity<List<Map<String, Object>>> response = restTemplate.exchange(
                url, HttpMethod.GET, requestEntity, new ParameterizedTypeReference<List<Map<String, Object>>>() {}
        );

        StringBuilder codeChanges = new StringBuilder();

        if (response.getBody() != null) {
            for (Map<String, Object> fileChange : response.getBody()) {
                String patch = (String) fileChange.get("patch");
                if (patch != null) {
                    codeChanges.append(patch).append("\n");
                }
            }
        }

        return codeChanges.toString();
    }


    private String findSwaggerFileName(String prCodeChanges) {
        // Common Swagger file names
        List<String> possibleSwaggerNames = List.of("swagger.json", "swagger.yaml", "openapi.json", "openapi.yaml");

        for (String fileName : possibleSwaggerNames) {
            if (prCodeChanges.contains(fileName)) {
                return fileName;
            }
        }

        // Default to "swagger.json" if none found in code changes (adjust as needed)
        return "swagger.json";
    }
}
