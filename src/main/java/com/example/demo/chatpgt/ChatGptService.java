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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
        System.out.println(evaluationResult);

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
                Generate initial contract test cases based on the following Swagger file content:
                
                Swagger File Content:
                %s
                
                Please focus on covering the following aspects:
                
                - Endpoint coverage, ensuring each endpoint has at least one test case.
                - Request and response validation, including expected status codes and response structures.
                - Required and optional parameters, along with any specific constraints or validation rules.
                - Expected outcomes based on each endpoint’s purpose.
                
                Use the following structure for each test case and ensure each field is formatted using appropriate Markdown:

                Test Case Structure:
                    ## Test Case ID: Unique identifier for the test case (e.g., `TC_EndpointName_StatusCode`) \s
                    **Endpoint**: Full URL path for the API endpoint \s
                    **Description**: Brief description of what this test case validates \s
                    **Preconditions**: Any setup or prerequisites required before executing the test \s
                    
                    **Request Data**: \s
                    - **Method**: HTTP method (e.g., GET, POST) \s
                    - **Headers**: Required headers with example values \s
                    - **Body Parameters**: List of parameters with sample values (if applicable) \s
                    
                    **Expected Response**: \s
                    - **Status Code**: Expected HTTP status code (e.g., 200, 400) \s
                    - **Response Body**: Example structure of the expected response, formatted in a JSON code block \s
                    
                        ```json
                        {
                            "key": "value",
                            "anotherKey": "anotherValue"
                        }
                        ```
                    
                    **Validation Rules**: Key response elements to validate, such as presence of specific fields, data types, or constraints.
                
                Ensure each test case is provided in a well-structured Markdown format. Use:
                - Double hashes (`##`) for the test case ID as a header.
                - Bold formatting (using `**`) for each field label (e.g., **Endpoint**, **Description**).
                - Code blocks (using triple backticks) for JSON data in the **Response Body** section.

                Please start directly with the first test case in the response and no additional text beforehand. Separate each test case by the following row exactly written like this:
                ROW_SEPARATOR
                """, swaggerContent);

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", "gpt-3.5-turbo");
        requestBody.put("messages", List.of(Map.of("role", "user", "content", prompt)));
        requestBody.put("temperature", 0.7);

        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);

        ResponseEntity<Map<String, Object>> response = restTemplate.exchange(apiUrl, HttpMethod.POST, entity, new ParameterizedTypeReference<Map<String, Object>>() {
        });

        Map<String, Object> choices = (Map<String, Object>) ((List<?>) response.getBody().get("choices")).get(0);
        String testCaseContent = (String) ((Map<String, Object>) choices.get("message")).get("content");

        System.out.println(testCaseContent);

        // Split the test cases from the response content and return it
        return splitTestCases(testCaseContent);
    }

    public void saveContractTestsCasesToDB(String swaggerFileName, List<String> contractTestCases) {
        for (String testCase: contractTestCases) {
            ContractTestCase testCaseEntity = new ContractTestCase(swaggerFileName, testCase);
            contractTestCaseRepository.save(testCaseEntity);
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
                apiUrl, HttpMethod.POST, entity, new ParameterizedTypeReference<Map<String, Object>>() {}
        );

        // Extract the markdown response content
        Map<String, Object> choices = (Map<String, Object>) ((List<?>) response.getBody().get("choices")).get(0);
        String markdownContent = (String) ((Map<String, Object>) choices.get("message")).get("content");

        return markdownContent;
    }

    public String generateUpdatedContractTestCases(String swaggerFileName, String prCodeChanges, String initialSwaggerContent) {
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
                Review the following inputs to update the contract test cases:

                Original Swagger File Content (Before Changes):
                %s

                Swagger File Changes from PR:
                %s

                Existing Contract Test Cases:
                %s

                Based on these details, please update the contract test cases to reflect the changes. Focus on the following:

                - Adjusting any endpoint coverage, request/response validation, or parameter requirements that have changed.
                - Adding new test cases if new endpoints, parameters, or response structures were introduced.
                - Removing or modifying test cases where requirements were removed or changed.
                - Ensure the updated test cases maintain comprehensive coverage of the updated API specification,
                accurately validate requests and responses, and reflect any new required or optional parameters and expected outcomes.
                
                Make sure to keep the following test case structure in place as the existing testcases were created based on this structure:
                
                Test Case Structure:
                    Test Case ID: Unique identifier for the test case (e.g., `TC_EndpointName_StatusCode`)
                    Endpoint: Full URL path for the API endpoint
                    Description: Brief description of what this test case validates
                    Preconditions: Any setup or prerequisites required before executing the test
                    Request Data:
                        Method: HTTP method (e.g., GET, POST)
                        Headers: Required headers with example values
                        Body Parameters: List of parameters with sample values (if applicable)
                    Expected Response:
                        Status Code: Expected HTTP status code (e.g., 200, 400)
                        Response Body: Example structure of the expected response, including data types and keys
                    Validation Rules: Key response elements to validate, such as presence of specific fields, data types, or constraints.
                    
                Please make sure that your response starts directly with the first testcase and no other text before. For example your respons should start like this:
                "Test Case ID: TC_EndpointName_StatusCode"
                """, initialSwaggerContent, prCodeChanges, existingTestCasesText);

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
            ContractTestCase testCaseEntity = new ContractTestCase(swaggerFileName, testCase);
            contractTestCaseRepository.save(testCaseEntity);
        }

        return convertTestCaseToMarkdownUsingOpenAI(testCaseContent);
    }

    public List<String> updateContractTestCases(String prCodeChanges, String swaggerFileName, String initialSwaggerContent) {
        String newTestCases = generateUpdatedContractTestCases(swaggerFileName, prCodeChanges, initialSwaggerContent);
        List<String> updatedTestCases = splitTestCases(newTestCases);
        return updatedTestCases;
    }

}
