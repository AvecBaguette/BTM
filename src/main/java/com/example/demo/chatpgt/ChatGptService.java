package com.example.demo.chatpgt;

import com.example.demo.database.entities.ContractTestCase;
import com.example.demo.database.entities.TestCase;
import com.example.demo.database.repositories.ContractTestCaseRepository;
import com.example.demo.database.repositories.TestCaseRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.*;

@Service
public class ChatGptService {

    @Value("${openai.api.key}")
    private String openAiApiKey;

    private final RestTemplate restTemplate;
    private final TestCaseRepository testCaseRepository;
    private final ContractTestCaseRepository contractTestCaseRepository;

    public ChatGptService(RestTemplate restTemplate, TestCaseRepository testCaseRepository, ContractTestCaseRepository contractTestCaseRepository) {
        this.restTemplate = restTemplate;
        this.testCaseRepository = testCaseRepository;
        this.contractTestCaseRepository = contractTestCaseRepository;
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

        ResponseEntity<Map<String, Object>> response = restTemplate.exchange(apiUrl, HttpMethod.POST, entity, new ParameterizedTypeReference<Map<String, Object>>() {
        });

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

        ResponseEntity<Map<String, Object>> response = restTemplate.exchange(apiUrl, HttpMethod.POST, entity, new ParameterizedTypeReference<Map<String, Object>>() {
        });

        Map<String, Object> choices = (Map<String, Object>) ((List<?>) response.getBody().get("choices")).get(0);
        String evaluationResult = (String) ((Map<String, Object>) choices.get("message")).get("content");

        // Return true if OpenAI says "No" (meaning no e2e test cases are needed)
        return evaluationResult.trim().equalsIgnoreCase("No");
    }

    private List<String> splitTestCases(String content) {
        List<String> testCases = new ArrayList<>();

        // Trim any trailing "ROW_SEPARATOR" and newline from the content
        content = content.replaceAll("(ROW_SEPARATOR\\R?)+$", "");

        // Split the content based on "ROW_SEPARATOR" followed by a newline
        String[] splitContent = content.split("ROW_SEPARATOR\\R");

        // Process each split part
        for (String testCase : splitContent) {
            // Trim and add each test case to the list if it's not empty
            testCase = testCase.trim();
            if (!testCase.isEmpty()) {
                testCases.add(testCase);
            }
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
        String existingTestCasesContent = existingTestCases.map(testCases -> {
            StringBuilder sb = new StringBuilder();
            sb.append("Here are the existing test cases for ").append(fileName).append(":\n\n");
            for (TestCase testCase : testCases) {
                sb.append(testCase.getTestCaseContent()).append("\n\n");
            }
            return sb.toString();
        }).orElse("No existing test cases found.");

        // Modify the prompt to include existing test cases and code changes
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

    public List<String> generateInitialContractTestCases(String swaggerName, String swaggerContent) {
        String apiUrl = "https://api.openai.com/v1/chat/completions";

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(openAiApiKey);
        headers.setContentType(MediaType.APPLICATION_JSON);

        // Prompt to generate manual contract test cases from Swagger content
        String prompt = String.format("""
    Generate a comprehensive set of initial contract test cases based on the following Swagger file content:

    Swagger File Content:
    %s

    ### Instructions:
    - Analyze the Swagger file to identify all endpoints, methods, and associated response codes.
    - For each method in the file, generate test cases for the following:
      1. **Successful scenarios**: At least one test case for each success response code (e.g., 200, 201).
      2. **Error scenarios**: At least one test case for each error response code (e.g., 400, 404).
      3. **Edge cases**:
         - For query parameters: Include boundary values, invalid inputs, and missing parameters.
         - For body parameters: Test required fields, invalid data types, and missing fields.
    - Ensure test cases include:
      - Endpoint and HTTP method.
      - Request details (headers, query/body parameters, etc.).
      - Expected response (status code, headers, and response body schema).
      - Validation rules for the response (schema compliance, specific fields).

    ### Test Case Structure:
    Use the following structure and format for each test case:

    ## Test Case ID: Unique identifier (e.g., `TC_<EndpointName>_<StatusCode>`)

    - **Endpoint**: Full API endpoint path (e.g., `/api/v1/resource`)
    - **Description**: Brief description of the test case's purpose.
    - **Preconditions**: Any setup or prerequisites.
    - **Request Data**:
      - **Method**: HTTP method (e.g., GET, POST)
      - **Headers**: Required headers with example values.
      - **Query Parameters**: Examples for valid and invalid values (if applicable).
      - **Body Parameters**: Sample payload for POST/PUT requests (if applicable).
    - **Expected Response**:
      - **Status Code**: Expected HTTP status code (e.g., 200, 400).
      - **Response Body**: Example JSON structure:
        ```
        {
          "key": "value",
          "anotherKey": "anotherValue"
        }
        ```
      - **Headers**: Required response headers (e.g., `Content-Type: application/json`).
    - **Validation Rules**:
      - Validate the presence and correctness of key response elements (e.g., fields, data types).
      - Ensure the response complies with the schema defined in the Swagger file.

    ### Additional Notes:
    - Generate **all necessary test cases** based on the file, covering every endpoint and response code.
    - Each test case must focus on **one specific scenario** (e.g., valid query, missing parameter, invalid ID).
    - Use Markdown formatting and separate each test case with the exact followin string: `ROW_SEPARATOR`.

    Begin directly with the first test case and include all required test cases, adhering to the structure above.
""", swaggerContent);

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", "gpt-3.5-turbo");
        requestBody.put("messages", List.of(Map.of("role", "user", "content", prompt)));
        requestBody.put("temperature", 0.0);

        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);

        ResponseEntity<Map<String, Object>> response = restTemplate.exchange(apiUrl, HttpMethod.POST, entity, new ParameterizedTypeReference<Map<String, Object>>() {
        });

        Map<String, Object> choices = (Map<String, Object>) ((List<?>) response.getBody().get("choices")).get(0);
        String testCaseContent = (String) ((Map<String, Object>) choices.get("message")).get("content");


        // Split the test cases from the response content and return it
        return splitTestCases(testCaseContent);
    }

    public void saveContractTestsCasesToDB(String swaggerFileName, List<String> contractTestCases) {
        for (String testCase : contractTestCases) {
            ContractTestCase testCaseEntity = new ContractTestCase(swaggerFileName, testCase);
            contractTestCaseRepository.save(testCaseEntity);
        }
    }

    public void updateContractTestCasesInDB(List<Map<String, String>> updatedTestCases) {
        for (Map<String, String> testCaseMap : updatedTestCases) {
            String testId = testCaseMap.get("id");
            String newContent = testCaseMap.get("tc_new");

            // Fetch the existing test case by ID
            Optional<ContractTestCase> optionalTestCase = contractTestCaseRepository.findById(Long.valueOf(testId));

            if (optionalTestCase.isPresent()) {
                ContractTestCase existingTestCase = optionalTestCase.get();

                // Update the test case content
                existingTestCase.setTestCaseContent(newContent);

                // Save the updated test case back to the database
                contractTestCaseRepository.save(existingTestCase);
            } else {
                // Log or handle the case where the test case ID does not exist
                System.out.println("Test case with ID " + testId + " not found.");
            }
        }
    }

    public String convertTestCaseToMarkdownUsingOpenAI(String testCaseContent) {
        String apiUrl = "https://api.openai.com/v1/chat/completions";
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(openAiApiKey); // Use your OpenAI API key here
        headers.setContentType(MediaType.APPLICATION_JSON);

        // Set up the prompt to request markdown formatting
        String prompt = "Convert the following test case into well-structured markdown format. Use headers for the test case ID, bold labels for each field, and code blocks for JSON data. Format each section clearly:\n\n" + testCaseContent;

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", "gpt-3.5-turbo");
        requestBody.put("messages", List.of(Map.of("role", "user", "content", prompt)));
        requestBody.put("temperature", 0.5);

        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);

        // Send the request to OpenAI API
        ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                apiUrl, HttpMethod.POST, entity, new ParameterizedTypeReference<Map<String, Object>>() {
                }
        );

        // Extract the markdown response content
        Map<String, Object> choices = (Map<String, Object>) ((List<?>) response.getBody().get("choices")).get(0);
        String markdownContent = (String) ((Map<String, Object>) choices.get("message")).get("content");

        return markdownContent;
    }

    public List<Map<String, String>> generateUpdatedContractTestCases(String swaggerFileName, String prCodeChanges, String initialSwaggerContent) {
        String apiUrl = "https://api.openai.com/v1/chat/completions";

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(openAiApiKey);
        headers.setContentType(MediaType.APPLICATION_JSON);

        List<ContractTestCase> existingTestCases = contractTestCaseRepository.findByFileName(swaggerFileName);
        StringBuilder existingTestCasesText = new StringBuilder();

        // Format the existing test cases into a string to include in the prompt
        existingTestCasesText.append("Here are the existing test cases:\n");
        for (ContractTestCase testCase : existingTestCases) {
            existingTestCasesText.append(testCase.getTestCaseContent()).append("\n\n");
        }

        // Construct the prompt with existing test cases included
        String prompt = String.format("""
    Update the existing contract test cases based on the following inputs:

    ### Inputs:
    1. **Initial Swagger File Content**:
    %s

    2. **PR Changes**:
    These are the changes made to the Swagger file in the pull request. Analyze these changes and determine how they affect the test cases:
    %s

    3. **Existing Test Cases in the Database**:
    These are the current test cases stored in the database:
    %s

    ### Instructions:
    - Analyze the PR changes to identify the endpoints, methods, or response codes that have been modified or removed.
    - For affected test cases:
      1. If the PR removes an endpoint, method, or response code, delete the corresponding test case.
      2. If the PR modifies an endpoint, method, or response code, update the corresponding test case fields (e.g., `Expected Response`, `Validation Rules`).
    - For test cases unrelated to the PR changes, return them **unchanged and in the exact same order**.

    ### Output Requirements:
    - Return the full updated list of test cases in the exact same order as the input list.
    - If a test case is removed, exclude it from the final output.
    - Use Markdown formatting for each test case.

    ### Test Case Structure:
    Ensure all test cases adhere to the following format:
    
    ### Test Case Structure:
    Use the following structure and format for each test case:

    ## Test Case ID: Unique identifier (e.g., `TC_<EndpointName>_<StatusCode>`)

    - **Endpoint**: Full API endpoint path (e.g., `/api/v1/resource`)
    - **Description**: Brief description of the test case's purpose.
    - **Preconditions**: Any setup or prerequisites.
    - **Request Data**:
      - **Method**: HTTP method (e.g., GET, POST)
      - **Headers**: Required headers with example values.
      - **Query Parameters**: Examples for valid and invalid values (if applicable).
      - **Body Parameters**: Sample payload for POST/PUT requests (if applicable).
    - **Expected Response**:
      - **Status Code**: Expected HTTP status code (e.g., 200, 400).
      - **Response Body**: Example JSON structure:
        ```
        {
          "key": "value",
          "anotherKey": "anotherValue"
        }
        ```
      - **Headers**: Required response headers (e.g., `Content-Type: application/json`).
    - **Validation Rules**:
      - Validate the presence and correctness of key response elements (e.g., fields, data types).
      - Ensure the response complies with the schema defined in the Swagger file.

    ### Notes:
    - Ensure the output contains the full updated list of test cases in the correct order.
    - Do not add or modify unrelated test cases.
    - Ensure that the output is formatted consistently and directly includes the test cases.
    - Use Markdown formatting and separate each test case with the exact followin string: `ROW_SEPARATOR`.
""", initialSwaggerContent, prCodeChanges, existingTestCases);

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", "gpt-3.5-turbo");
        requestBody.put("messages", List.of(Map.of("role", "user", "content", prompt)));
        requestBody.put("temperature", 0.0);

        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);

        ResponseEntity<Map<String, Object>> response = restTemplate.exchange(apiUrl, HttpMethod.POST, entity, new ParameterizedTypeReference<Map<String, Object>>() {
        });

        Map<String, Object> choices = (Map<String, Object>) ((List<?>) response.getBody().get("choices")).get(0);
        String testCaseContent = (String) ((Map<String, Object>) choices.get("message")).get("content");


        List<String> testCasesList = splitTestCases(testCaseContent);

        List<Map<String, String>> combinedList = new ArrayList<>();

        for (int i = 0; i < testCasesList.size(); i++) {
            String newTestCase = testCasesList.get(i);
            String oldTestCase = existingTestCases.get(i).getTestCaseContent();
            String id = String.valueOf(existingTestCases.get(i).getId());
            Map<String, String> jsonEntry = new HashMap<>();
            jsonEntry.put("id", id);
            jsonEntry.put("tc_old", oldTestCase);
            jsonEntry.put("tc_new", newTestCase);
            combinedList.add(jsonEntry);
        }

        return combinedList;
    }

    public List<Map<String, String>> updateContractTestCases(String prCodeChanges, String swaggerFileName, String initialSwaggerContent) {
        List<Map<String, String>> newTestCases = generateUpdatedContractTestCases(swaggerFileName, prCodeChanges, initialSwaggerContent);
        return newTestCases;
    }

}
