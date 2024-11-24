package com.example.demo.github;

import com.example.demo.chatpgt.ChatGptService;
import com.example.demo.database.entities.ContractTestCase;
import com.example.demo.database.repositories.ContractTestCaseRepository;
import org.jetbrains.annotations.NotNull;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
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

    @Autowired
    private ContractTestCaseRepository contractTestCaseRepository;

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
                reposUrl, HttpMethod.GET, requestEntity, new ParameterizedTypeReference<List<Map<String, Object>>>() {
                }
        );

        // Extract just the repository names
        List<String> repoNames = response.getBody().stream()
                .map(repo -> (String) repo.get("name"))
                .collect(Collectors.toList());

        return ResponseEntity.ok(repoNames);
    }

    @GetMapping("/user/repo-owner")
    public ResponseEntity<String> getRepositoryOwner(
            @RequestParam("access_token") String accessToken,
            @RequestParam("repo_name") String repoName) {

        String reposUrl = "https://api.github.com/user/repos";

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        headers.setAccept(Collections.singletonList(MediaType.APPLICATION_JSON));

        HttpEntity<String> requestEntity = new HttpEntity<>(headers);

        ResponseEntity<List<Map<String, Object>>> response = restTemplate.exchange(
                reposUrl, HttpMethod.GET, requestEntity, new ParameterizedTypeReference<List<Map<String, Object>>>() {
                }
        );

        if (response.getStatusCode() == HttpStatus.OK) {
            List<Map<String, Object>> repositories = response.getBody();

            // Loop through repositories to find the one matching the provided repoName
            for (Map<String, Object> repo : repositories) {
                if (repoName.equals(repo.get("name"))) {
                    Map<String, Object> owner = (Map<String, Object>) repo.get("owner");
                    String ownerName = (String) owner.get("login");
                    return ResponseEntity.ok(ownerName); // Return the owner's username
                }
            }
        }
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body("Repository not found");
    }

    @GetMapping("/user/repo/prs")
    public ResponseEntity<List<Map<String, String>>> getActivePullRequests(
            @RequestParam("owner") String owner,
            @RequestParam("access_token") String accessToken,
            @RequestParam("repo_name") String repoName) {

        String prsUrl = "https://api.github.com/repos/{owner}/{repo}/pulls";

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));

        HttpEntity<String> requestEntity = new HttpEntity<>(headers);

        String formattedUrl = prsUrl.replace("{owner}", owner).replace("{repo}", repoName);

        ResponseEntity<List<Map<String, Object>>> response = restTemplate.exchange(
                formattedUrl, HttpMethod.GET, requestEntity, new ParameterizedTypeReference<List<Map<String, Object>>>() {}
        );

        // Extract PR titles and creation dates from active PRs
        List<Map<String, String>> prDetails = response.getBody().stream()
                .filter(pr -> "open".equals(pr.get("state")))
                .map(pr -> Map.of(
                        "title", (String) pr.get("title"),
                        "creation_date", (String) pr.get("created_at")
                ))
                .collect(Collectors.toList());

        return ResponseEntity.ok(prDetails);
    }

    @GetMapping("/generate-contract-test-cases")
    public ResponseEntity<?> generateContractTestCases(
            @RequestParam String owner,
            @RequestParam String repo_name,
            @RequestParam String access_token) {
        // Fetch all files from the repository
        Map<String, String> fileContents = fetchAllFilesFromRepo(owner, repo_name, access_token);

        // Generate initial contract test cases for the Swagger file
        Result result = getResult(fileContents);
        List<String> contractTestCases = chatGptService.generateInitialContractTestCases(result.fileName(), result.content());

        // Check if the list is empty and return a message if so
        if (contractTestCases.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body("No contract test cases were generated.");
        }
        //return the list of generated tests
        return ResponseEntity.ok(contractTestCases);
    }

    @PostMapping("/save-contract-test-cases")
    public ResponseEntity<?> saveContractTestCases(
            @RequestParam String owner,
            @RequestParam String repo_name,
            @RequestParam String access_token,
            @RequestBody List<String> contractTestCases) {

        // Check if the received list is empty
        if (contractTestCases == null || contractTestCases.isEmpty()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body("The list of contract test cases is empty.");
        }

        Map<String, String> fileContents = fetchAllFilesFromRepo(owner, repo_name, access_token);
        Result result = getResult(fileContents);

        chatGptService.saveContractTestsCasesToDB(result.fileName(), contractTestCases, repo_name);

        return ResponseEntity.status(HttpStatus.CREATED)
                .body("Contract test cases have been saved successfully.");
    }

    @PostMapping("/update-contract-test-cases")
    public ResponseEntity<?> updateContractTestCases(
            @RequestParam String owner,
            @RequestParam String repo_name,
            @RequestParam String access_token,
            @RequestBody List<Map<String,String>> updatedContractTestCases) {

        // Check if the received list is empty
        if (updatedContractTestCases == null || updatedContractTestCases.isEmpty()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body("The list of contract test cases is empty.");
        }

        Map<String, String> fileContents = fetchAllFilesFromRepo(owner, repo_name, access_token);
        Result result = getResult(fileContents);

        chatGptService.updateContractTestCasesInDB(updatedContractTestCases, repo_name);

        return ResponseEntity.status(HttpStatus.CREATED)
                .body("Contract test cases have been saved successfully.");
    }

    @NotNull
    private static Result getResult(Map<String, String> fileContents) {
        Map.Entry<String, String> swaggerFileEntry = fileContents.entrySet().stream()
                .filter(entry -> entry.getKey().toLowerCase().contains("swagger") || entry.getKey().toLowerCase().contains("openapi"))
                .findFirst()
                .orElseThrow(() -> new RuntimeException("No Swagger or OpenAPI file found"));

        String fileName = swaggerFileEntry.getKey();
        String content = swaggerFileEntry.getValue();
        return new Result(fileName, content);
    }

    private record Result(String fileName, String content) {
    }

    @GetMapping("/generate-updated-contract-test-cases")
    public ResponseEntity<?> updateContractTestCases(
            @RequestParam String owner,
            @RequestParam String repo_name,
            @RequestParam String access_token,
            @RequestParam String pr_name) {

        // Step 1: Retrieve the list of open PRs for the repository
        List<Map<String, Object>> openPRs = fetchOpenPullRequests(owner, repo_name, access_token);

        // Step 2: Find the PR by name
        Map<String, Object> targetPR = openPRs.stream()
                .filter(pr -> pr_name.equals(pr.get("title")))
                .findFirst()
                .orElseThrow(() -> new RuntimeException("PR not found"));

        // Step 3: Retrieve the code changes for the specified PR
        String updatedSwaggerFile = getPRCodeChanges(owner, repo_name, access_token, (Integer) targetPR.get("number"));
        // Step 4: Identify Swagger file name (assuming it's in the code changes or repository)
        String swaggerFileName = findSwaggerFileName(updatedSwaggerFile); // Custom method to locate Swagger file

        // Step 5: Retrieve the original Swagger file content (before changes)
        String swaggerContent = getOriginalSwaggerContent(owner, repo_name, access_token);
        // Step 6: Call the OpenAI service method to get updated contract test cases
        List<Map<String, String>> updatedTestCases = chatGptService.updateContractTestCases(updatedSwaggerFile, swaggerFileName, swaggerContent);

        // Step 7: Return the list of updated test cases
        return ResponseEntity.ok(updatedTestCases);
    }

    // Placeholder method for fetching the original Swagger content from a repository or source
    private String getOriginalSwaggerContent(String owner, String repoName, String accessToken) {
        Map<String, String> fileContents = fetchAllFilesFromRepo(owner, repoName, accessToken);
        Result result = getResult(fileContents);
        return result.content();
    }

    private List<Map<String, Object>> fetchOpenPullRequests(String owner, String repo, String accessToken) {
        String url = "https://api.github.com/repos/" + owner + "/" + repo + "/pulls?state=open";

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));

        HttpEntity<String> requestEntity = new HttpEntity<>(headers);

        ResponseEntity<List<Map<String, Object>>> response = restTemplate.exchange(
                url, HttpMethod.GET, requestEntity, new ParameterizedTypeReference<List<Map<String, Object>>>() {}
        );

        return response.getBody() != null ? response.getBody() : new ArrayList<>();
    }

    private String getPRCodeChanges(String owner, String repo, String accessToken, int prNumber) {
        // Step 1: Fetch the PR metadata to get the source branch
        String prUrl = "https://api.github.com/repos/" + owner + "/" + repo + "/pulls/" + prNumber;
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));

        HttpEntity<String> requestEntity = new HttpEntity<>(headers);

        ResponseEntity<Map<String, Object>> prResponse = restTemplate.exchange(
                prUrl, HttpMethod.GET, requestEntity, new ParameterizedTypeReference<Map<String, Object>>() {}
        );

        if (prResponse.getBody() == null || !prResponse.getBody().containsKey("head")) {
            throw new IllegalStateException("Unable to fetch PR metadata or source branch.");
        }

        String prBranch = (String) ((Map<String, Object>) prResponse.getBody().get("head")).get("ref");

        // Step 2: Fetch the list of files changed in the PR
        String filesUrl = "https://api.github.com/repos/" + owner + "/" + repo + "/pulls/" + prNumber + "/files";
        ResponseEntity<List<Map<String, Object>>> filesResponse = restTemplate.exchange(
                filesUrl, HttpMethod.GET, requestEntity, new ParameterizedTypeReference<List<Map<String, Object>>>() {}
        );

        if (filesResponse.getBody() == null || filesResponse.getBody().isEmpty()) {
            throw new IllegalStateException("No files changed in the PR.");
        }

        StringBuilder fullChangedFilesContent = new StringBuilder();

        // Step 3: Fetch the full content of each changed file from the PR branch
        for (Map<String, Object> fileChange : filesResponse.getBody()) {
            String filePath = (String) fileChange.get("filename");

            String fileContentUrl = "https://api.github.com/repos/" + owner + "/" + repo + "/contents/" + filePath + "?ref=" + prBranch;
            ResponseEntity<Map<String, Object>> fileContentResponse = restTemplate.exchange(
                    fileContentUrl, HttpMethod.GET, requestEntity, new ParameterizedTypeReference<Map<String, Object>>() {}
            );

            if (fileContentResponse.getBody() != null) {
                String encodedContent = (String) fileContentResponse.getBody().get("content");
                try {
                    // Clean and decode the Base64 content
                    byte[] decodedBytes = Base64.getMimeDecoder().decode(encodedContent.replaceAll("\\s+", ""));
                    String fileContent = new String(decodedBytes, StandardCharsets.UTF_8);

                    fullChangedFilesContent.append("### File: ").append(filePath).append("\n")
                            .append(fileContent).append("\n\n");
                } catch (IllegalArgumentException e) {
                    throw new IllegalStateException("Failed to decode Base64 content for file: " + filePath, e);
                }
            }
        }

        return fullChangedFilesContent.toString();
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

    @GetMapping("/contract-test-cases")
    public ResponseEntity<List<Map<String, String>>> getAllTestCases(
            @RequestParam(value = "test_repo", required = false) String testRepo) {
        List<ContractTestCase> testCases;

        // Retrieve test cases based on whether testRepo is provided
        if (testRepo != null && !testRepo.isEmpty()) {
            testCases = contractTestCaseRepository.findByTestRepo(testRepo);
        } else {
            testCases = contractTestCaseRepository.findAll();
        }

        // Map the test cases to the desired JSON structure
        List<Map<String, String>> response = testCases.stream().map(testCase -> {
            Map<String, String> testCaseJson = new HashMap<>();
            testCaseJson.put("id", String.valueOf(testCase.getId()));
            testCaseJson.put("title", testCase.getTitle());
            testCaseJson.put("content", testCase.getTestCaseContent());
            return testCaseJson;
        }).collect(Collectors.toList());

        // Return the response
        return ResponseEntity.ok(response);
    }

}
