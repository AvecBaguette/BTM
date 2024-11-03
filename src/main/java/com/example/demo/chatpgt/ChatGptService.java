package com.example.demo.chatpgt;

import com.example.demo.database.entities.TestCase;
import com.example.demo.database.repositories.TestCaseRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class ChatGptService {

    @Value("${openai.api.key}")
    private String openAiApiKey;

    private final RestTemplate restTemplate;
    private final TestCaseRepository testCaseRepository;

    public ChatGptService(RestTemplate restTemplate, TestCaseRepository testCaseRepository) {
        this.restTemplate = restTemplate;
        this.testCaseRepository = testCaseRepository;
    }

    public String generateTestCases(String fileName, String fileContent) {
        // Use OpenAI to determine if the content warrants e2e test cases
        if (isNonE2ETestableContent(fileContent)) {
            // Skip test case generation if OpenAI determines no e2e tests are needed
            return "No e2e test cases generated: OpenAI deemed the content irrelevant for e2e testing.";
        }

        String apiUrl = "https://api.openai.com/v1/chat/completions";

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(openAiApiKey);
        headers.setContentType(MediaType.APPLICATION_JSON);

        // Prompt for e2e test case generation excluding styling/layout
        String prompt = "Generate only end-to-end test cases for the following code. Focus on functional and interactive tests that simulate real user interactions. Ignore test cases that check CSS styling, layout properties, or static appearance of elements, as these do not require end-to-end testing: " + fileContent;

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", "gpt-3.5-turbo");
        requestBody.put("messages", List.of(Map.of("role", "user", "content", prompt)));
        requestBody.put("temperature", 0.7);

        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);

        ResponseEntity<Map<String, Object>> response = restTemplate.exchange(apiUrl, HttpMethod.POST, entity, new ParameterizedTypeReference<Map<String, Object>>() {});

        Map<String, Object> choices = (Map<String, Object>) ((List<?>) response.getBody().get("choices")).get(0);
        String testCaseContent = (String) ((Map<String, Object>) choices.get("message")).get("content");

        // Split the test cases from the response content
        List<String> testCases = splitTestCases(testCaseContent);

        // Save each test case in the database
        for (String testCase : testCases) {
            TestCase testCaseEntity = new TestCase(fileName, testCase);
            testCaseRepository.save(testCaseEntity);
        }

        return testCaseContent;
    }

    // New helper method to use OpenAI to determine if e2e test cases are necessary
    private boolean isNonE2ETestableContent(String fileContent) {
        String apiUrl = "https://api.openai.com/v1/chat/completions";

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(openAiApiKey);
        headers.setContentType(MediaType.APPLICATION_JSON);
        // Prompt OpenAI to evaluate whether e2e tests or already existing code changes are necessary
        String prompt = "Analyze the following code content to determine if it requires end-to-end (e2e) test cases." +
                " Suggest e2e tests only if the code includes interactive or functional components that users interact with directly. " +
                "If the content solely covers styling, layout, or static display, respond with 'No' as e2e tests are unnecessary." +
                " Answer 'Yes' if e2e tests are needed and 'No' if not:\\n\\n" + fileContent;

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", "gpt-3.5-turbo");
        requestBody.put("messages", List.of(Map.of("role", "user", "content", prompt)));
        requestBody.put("temperature", 0.0); // Set to 0 for deterministic answer

        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);

        ResponseEntity<Map<String, Object>> response = restTemplate.exchange(apiUrl, HttpMethod.POST, entity, new ParameterizedTypeReference<Map<String, Object>>() {});

        Map<String, Object> choices = (Map<String, Object>) ((List<?>) response.getBody().get("choices")).get(0);
        String evaluationResult = (String) ((Map<String, Object>) choices.get("message")).get("content");
        System.out.println(evaluationResult);

        // Return true if OpenAI says "No" (meaning no e2e test cases are needed)
        return evaluationResult.trim().equalsIgnoreCase("No");
    }

    private List<String> splitTestCases(String content) {
        List<String> testCases = new ArrayList<>();

        // Updated regex to ensure test case markers start at a new line or the beginning
        Pattern pattern = Pattern.compile("(?<=^|\\n)\\d+\\.\\s"); // Match numbers + ". " only at start or after a line break
        Matcher matcher = pattern.matcher(content);

        List<Integer> indices = new ArrayList<>();
        while (matcher.find()) {
            indices.add(matcher.start());
        }

        // Add the last index to ensure the last test case is captured
        indices.add(content.length());

        // Split the content based on the indices of the test case markers
        for (int i = 0; i < indices.size() - 1; i++) {
            String testCase = content.substring(indices.get(i), indices.get(i + 1)).trim();
            testCases.add(testCase);
        }

        return testCases;
    }

    public String generateTestCasesForChanges(String fileName, String codeChanges) {
        String apiUrl = "https://api.openai.com/v1/chat/completions";

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(openAiApiKey);
        headers.setContentType(MediaType.APPLICATION_JSON);

        // Fetch existing test cases from the database
        Optional<List<TestCase>> existingTestCases = testCaseRepository.findByFileName(fileName);
        System.out.println(existingTestCases);
        String existingTestCasesContent = existingTestCases.map(testCases -> {
            StringBuilder sb = new StringBuilder();
            sb.append("Here are the existing test cases for ").append(fileName).append(":\n\n");
            for (TestCase testCase : testCases) {
                sb.append(testCase.getTestCaseContent()).append("\n\n");
            }
            return sb.toString();
        }).orElse("No existing test cases found.");

        // Modify the prompt to include existing test cases and code changes
        System.out.println(existingTestCasesContent);
        String prompt = "The file " + fileName + " has the following changes:\n" + codeChanges +
                "\n Right now, I have the following manual testcases created before these code changes were made" +
                "\n\n" + existingTestCasesContent +
                "\nPlease update the existing manual test cases based on these changes, or add new test cases as necessary.";

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", "gpt-3.5-turbo");
        requestBody.put("messages", List.of(Map.of("role", "user", "content", prompt)));
        requestBody.put("temperature", 0.7);

        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);

        ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                apiUrl,
                HttpMethod.POST,
                entity,
                new ParameterizedTypeReference<Map<String, Object>>() {
                }
        );

        Map<String, Object> choices = (Map<String, Object>) ((List<?>) response.getBody().get("choices")).get(0);
        String updatedTestCasesContent = (String) ((Map<String, Object>) choices.get("message")).get("content");

        TestCase updatedTestCase = new TestCase(fileName, updatedTestCasesContent);
        testCaseRepository.save(updatedTestCase);

        return updatedTestCasesContent;
    }
}
