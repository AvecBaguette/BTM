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
        // Build the prompt for Gemini
        String prompt = """
        Generate one contract test case for each endpoint and each response status code explicitly defined in the provided Swagger (OpenAPI) specification. Adhere strictly to the following structure and ensure the following rules are met:

        ## Rules
        1. Generate **one test case for each combination of endpoint and response status code** explicitly defined in the Swagger specification.
        2. Use the exact `ROW_SEPARATOR` string to separate test cases.
        3. Ensure the output adheres strictly to the format in the example provided below.
        4. Use meaningful preconditions, request data, and validation rules based on the Swagger file.
        5. Include both positive and negative scenarios only when explicitly specified in the Swagger file.
        6. Do not create test cases for response codes that are not listed in the Swagger file.
        7. Ensure all fields are filled using data from the Swagger file, including examples where available.

        ## Example Input
        Swagger Specification:
        ```
        {
          "openapi": "3.0.0",
          "paths": {
            "/example": {
              "get": {
                "summary": "Retrieve example",
                "responses": {
                  "200": {
                    "description": "Successful response",
                    "content": {
                      "application/json": {
                        "example": { "id": 1, "name": "example" }
                      }
                    }
                  },
                  "404": {
                    "description": "Example not found"
                  }
                }
              }
            }
          }
        }
        ```

        ## Example Output
        ### Test Case Title: TC_example_200

        - **Endpoint**: /example
        - **Description**: Verify successful retrieval of an example.
        - **Preconditions**: Ensure the example resource exists.
        - **Request Data**:
            - **Method**: GET
            - **Headers**: Content-Type: application/json
            - **Query Parameters**: N/A
            - **Path Parameters**: N/A
            - **Body Parameters**: N/A
        - **Expected Response**:
            - **Status Code**: 200
            - **Response Body**:
                ```
                {
                  "id": 1,
                  "name": "example"
                }
                ```
            - **Headers**: Content-Type: application/json
        - **Validation Rules**:
            - Schema validation for the response body.
            - Ensure `id` and `name` fields are present and of correct type.
            - Positive scenario with valid data.

        ROW_SEPARATOR

        ### Test Case Title: TC_example_404

        - **Endpoint**: /example
        - **Description**: Verify error response when the requested example is not found.
        - **Preconditions**: No example resource exists with the specified ID.
        - **Request Data**:
            - **Method**: GET
            - **Headers**: Content-Type: application/json
            - **Query Parameters**: N/A
            - **Path Parameters**: N/A
            - **Body Parameters**: N/A
        - **Expected Response**:
            - **Status Code**: 404
            - **Response Body**: N/A
            - **Headers**: N/A
        - **Validation Rules**:
            - Verify the response status code is 404.
            - Negative scenario for a non-existent resource.

        ROW_SEPARATOR

        Now, based on the provided Swagger specification, generate test cases in the same format as the example output. Use only the response codes explicitly defined in the Swagger file for each endpoint. Ensure strict adherence to the `ROW_SEPARATOR` and test case structure.
        Here is the Swagger file content:
        """ + swaggerContent;


        // Gemini API configuration

        String geminiApiKey = "AIzaSyDtjPcQ9ikOzehH1nEA7o3fFIWx-ISZDVo";
        String geminiApiUrl = "https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash-latest:generateContent?key=" + geminiApiKey;

        // Prepare the request body
        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("contents", List.of(
                Map.of("parts", List.of(Map.of("text", prompt)))
        ));

        // Set up headers
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        // Create the HTTP entity
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);

        // Send the request
        RestTemplate restTemplate = new RestTemplate();
        ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                geminiApiUrl,
                HttpMethod.POST,
                entity,
                new ParameterizedTypeReference<>() {}
        );

        // https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash-latest:generateContent
        // Handle the response
        Map<String, Object> responseBody = response.getBody();

// Extract the "candidates" list from the response
        List<Map<String, Object>> candidates = (List<Map<String, Object>>) responseBody.get("candidates");

// Ensure there is at least one candidate
        if (candidates == null || candidates.isEmpty()) {
            throw new RuntimeException("No candidates found in response.");
        }

// Extract the "content" from the first candidate
        Map<String, Object> content = (Map<String, Object>) candidates.get(0).get("content");
        if (content == null || content.isEmpty()) {
            throw new RuntimeException("No content found in the first candidate.");
        }

// Extract the "parts" list from the content
        List<Map<String, Object>> parts = (List<Map<String, Object>>) content.get("parts");
        if (parts == null || parts.isEmpty()) {
            throw new RuntimeException("No parts found in content.");
        }

// Extract the "text" field from the first part
        String testCaseContent = (String) parts.get(0).get("text");

        // Split the test cases by ROW_SEPARATOR
        return splitTestCases(testCaseContent);
    }

    public void saveContractTestsCasesToDB(String swaggerFileName, List<String> contractTestCases, String repoName) {
        for (String testCase : contractTestCases) {
            // Extract the Test Case ID from the test case content
            String testCaseTitle = extractTestCaseTitle(testCase);

            // Save the test case with its ID as the title
            ContractTestCase testCaseEntity = new ContractTestCase(swaggerFileName, testCase, testCaseTitle, repoName);
            contractTestCaseRepository.save(testCaseEntity);
        }
    }

    /**
     * Extracts the Test Case ID from the test case content.
     * Assumes the ID follows the format: "## Test Case ID: Unique identifier"
     */
    private String extractTestCaseTitle(String testCase) {
        String testCaseTitle = "Untitled"; // Default title if ID is not found
        String pattern = "## Test Case Title: ([^\\n]+)";
        Pattern regex = Pattern.compile(pattern);
        Matcher matcher = regex.matcher(testCase);

        if (matcher.find()) {
            testCaseTitle = matcher.group(1).trim();
        }

        return testCaseTitle;
    }

    public void updateContractTestCasesInDB(List<Map<String, String>> updatedTestCases, String repoName) {
        for (Map<String, String> testCaseMap : updatedTestCases) {
            String testId = testCaseMap.getOrDefault("id", "").trim();
            String newContent = testCaseMap.getOrDefault("tc_new", "").trim();

            // UPDATED: All fields are populated
            if (!testId.isEmpty() && !newContent.isEmpty()) {
                Optional<ContractTestCase> optionalTestCase = contractTestCaseRepository.findById(Long.valueOf(testId));
                if (optionalTestCase.isPresent()) {
                    ContractTestCase existingTestCase = optionalTestCase.get();
                    existingTestCase.setTestCaseContent(newContent);

                    // Extract the new Test Case Title
                    String newTestCaseTitle = extractTestCaseTitle(newContent);
                    existingTestCase.setTitle(newTestCaseTitle);
                    existingTestCase.setTestRepo(repoName);

                    contractTestCaseRepository.save(existingTestCase);
                } else {
                    System.out.println("Test case with ID " + testId + " not found for update.");
                }
            }
            // ADDED: id and tc_old are empty; only newContent is populated
            else if (testId.isEmpty() && !newContent.isEmpty()) {
                ContractTestCase newTestCase = new ContractTestCase();
                newTestCase.setTestCaseContent(newContent);

                // Extract the Test Case Title
                String newTestCaseTitle = extractTestCaseTitle(newContent);
                newTestCase.setTitle(newTestCaseTitle);
                newTestCase.setTestRepo(repoName);

                contractTestCaseRepository.save(newTestCase);
            }
            // DELETED: newContent is empty; testId and oldContent are populated
            else if (!testId.isEmpty() && newContent.isEmpty()) {
                Optional<ContractTestCase> optionalTestCase = contractTestCaseRepository.findById(Long.valueOf(testId));
                if (optionalTestCase.isPresent()) {
                    contractTestCaseRepository.deleteById(Long.valueOf(testId));
                } else {
                    System.out.println("Test case with ID " + testId + " not found for deletion.");
                }
            }
            // SAME: Skip it (not explicitly mentioned in the input, but implied)
            else {
                System.out.println("Skipping test case with ID " + testId + ": no action required.");
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

    public List<Map<String, String>> generateUpdatedContractTestCases(String swaggerFileName, String updatedSwaggerFile, String initialSwaggerFile) {
        List<ContractTestCase> existingTestCases = contractTestCaseRepository.findByFileName(swaggerFileName);
        StringBuilder existingTestCasesText = new StringBuilder();

        // Format the existing test cases into a string to include in the prompt
        for (ContractTestCase testCase : existingTestCases) {
            existingTestCasesText.append(testCase.getTestCaseContent()).append("\n");
            existingTestCasesText.append("ID: ").append(testCase.getId()).append("\n\n");
        }

        // Construct the prompt with existing test cases included
        String prompt = """
Compare the 2 Swagger files and update the testcase list as needed based on Swagger changes.
There are 4 possible cases: test cases that stay the same (SAME),
test cases that need update (UPDATED), test cases that were added (ADDED),
test cases that were deleted (DELETED).

Swagger before changes:
""" + initialSwaggerFile + """

Swagger after changes:
""" + updatedSwaggerFile + """

Existing test cases:
""" + existingTestCasesText + """

## Instructions:
1. Compare the two Swagger files (`Swagger before changes` and `Swagger after changes`) to identify:
   - New endpoints or responses added.
   - Endpoints or responses deleted.
   - Any changes to endpoints or response details (e.g., updated status codes, descriptions, or schemas).
   - Endpoints or responses that remain unchanged.

2. Update the test cases accordingly:
   - **SAME**: If a test case is unaffected by the changes, keep it the same.
   - **UPDATED**: If a test case is affected by a change (e.g., updated response description or status code), update the test case to reflect the new details.
   - **ADDED**: For new endpoints or responses in the Swagger, add new test cases.
   - **DELETED**: For deleted endpoints or responses, mark the test case with "ChangeType: DELETED" and include only the Test Case Title in the output.

3. Output format:
   - Use the same structure as provided in the example below.
   - For new test cases (ADDED), include the full test case details.
   - For updated test cases (UPDATED), ensure the changes are highlighted in the test case details.
   - For deleted test cases (DELETED), only the test case Name and "ChangeType: DELETED" should appear.
   - For the ID field, only add it for SAME, UPDATED, DELETED testcases. Example: "- ID: 3". For ADDED changeType, keep the "- ChangeType: ADDED" in place.

## Output Example:
### Test Case Title: TC_accounts_get_200
- **Endpoint**: /accounts
- **Description**: Verify successful retrieval of all accounts.
- **Preconditions**: Ensure at least one account exists in the database.
- **Request Data**:
    - **Method**: GET
    - **Headers**: Content-Type: application/json
    - **Query Parameters**: N/A
    - **Path Parameters**: N/A
    - **Body Parameters**: N/A
- **Expected Response**:
    - **Status Code**: 200
    - **Response Body**: A list of account objects with "id" and "balance".
    - **Headers**: Content-Type: application/json
- **Validation Rules**:
    - Schema validation for the response body.
    - Ensure the response is an array of Account objects.
- ChangeType: SAME
- ID: 1

ROW_SEPARATOR

### Test Case Title: TC_accounts_post_201
- **Endpoint**: /accounts
- **Description**: Verify successful addition of a new account.
- **Preconditions**: Ensure the account does not already exist.
- **Request Data**:
    - **Method**: POST
    - **Headers**: Content-Type: application/json
    - **Query Parameters**: N/A
    - **Path Parameters**: N/A
    - **Body Parameters**: A valid account object.
- **Expected Response**:
    - **Status Code**: 201
    - **Response Body**: A success message indicating the account was added successfully.
    - **Headers**: Content-Type: application/json
- **Validation Rules**:
    - Verify the response status code is 201.
    - Verify the response body contains the success message.
- ChangeType: SAME
- ID: 2

ROW_SEPARATOR

### Test Case Title: TC_accounts_accountId_get_200
- **Endpoint**: /accounts/{accountId}
- **Description**: Verify successful retrieval of account details.
- **Preconditions**: Ensure the accountId exists in the database.
- **Request Data**:
    - **Method**: GET
    - **Headers**: Content-Type: application/json
    - **Query Parameters**: N/A
    - **Path Parameters**: accountId=123
    - **Body Parameters**: N/A
- **Expected Response**:
    - **Status Code**: 200
    - **Response Body**: An account object with "id" and "balance".
    - **Headers**: Content-Type: application/json
- **Validation Rules**:
    - Schema validation for the response body.
    - Ensure the response contains the correct account details.
- ChangeType: UPDATED
- ID: 3

ROW_SEPARATOR

### Test Case Title: TC_accounts_accountId_get_404
- **Endpoint**: /accounts/{accountId}
- **Description**: Verify the system returns a 404 error when an account is not found.
- **Preconditions**: Ensure the `accountId` does not exist in the database.
- **Request Data**:
    - **Method**: GET
    - **Headers**: Content-Type: application/json
    - **Query Parameters**: N/A
    - **Path Parameters**: accountId=999
    - **Body Parameters**: N/A
- **Expected Response**:
    - **Status Code**: 404
    - **Response Body**: { "error": "Account not found" }
    - **Headers**: Content-Type: application/json
- **Validation Rules**:
    - Verify the response status code is 404.
    - Verify the error message in the response body matches "Account not found".
    - Verify the response body schema for error format.
- ChangeType: ADDED

ROW_SEPARATOR

### Test Case Title: TC_transactions_get_200
- ChangeType: DELETED
- ID: 4

ROW_SEPARATOR

### Test Case Title: TC_transactions_post_201
- **Endpoint**: /transactions
- **Description**: Verify successful creation of a transaction.
- **Preconditions**: Ensure the transaction request contains valid data.
- **Request Data**:
    - **Method**: POST
    - **Headers**: Content-Type: application/json
    - **Query Parameters**: N/A
    - **Path Parameters**: N/A
    - **Body Parameters**: A valid transaction object with "id", "amount", and "type".
- **Expected Response**:
    - **Status Code**: 201
    - **Response Body**: A success message indicating the transaction was created successfully.
    - **Headers**: Content-Type: application/json
- **Validation Rules**:
    - Verify the response status code is 201.
    - Verify the success message in the response body.
- ChangeType: ADDED

ROW_SEPARATOR

## Your task:
Using the above example as a reference, compare the provided Swagger files and existing test cases. Then update the test cases as needed. Include only the updated, added, or deleted test cases in your output. For unchanged test cases, mark them with "ChangeType: SAME" and provide their full details.
""";


// Gemini API configuration

        String geminiApiKey = "AIzaSyDtjPcQ9ikOzehH1nEA7o3fFIWx-ISZDVo";
        String geminiApiUrl = "https://generativelanguage.googleapis.com/v1beta/tunedModels/btm-update-v2-se03t33i799f:generateContent?key=" + geminiApiKey;

        // Prepare the request body
        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("contents", List.of(
                Map.of("parts", List.of(Map.of("text", prompt)))
        ));

        // Set up headers
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        // Create the HTTP entity
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);

        // Send the request
        RestTemplate restTemplate = new RestTemplate();
        ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                geminiApiUrl,
                HttpMethod.POST,
                entity,
                new ParameterizedTypeReference<>() {}
        );

        // Handle the response
        Map<String, Object> responseBody = response.getBody();

// Extract the "candidates" list from the response
        List<Map<String, Object>> candidates = (List<Map<String, Object>>) responseBody.get("candidates");

// Ensure there is at least one candidate
        if (candidates == null || candidates.isEmpty()) {
            throw new RuntimeException("No candidates found in response.");
        }

// Extract the "content" from the first candidate
        Map<String, Object> content = (Map<String, Object>) candidates.get(0).get("content");
        if (content == null || content.isEmpty()) {
            throw new RuntimeException("No content found in the first candidate.");
        }

// Extract the "parts" list from the content
        List<Map<String, Object>> parts = (List<Map<String, Object>>) content.get("parts");
        if (parts == null || parts.isEmpty()) {
            throw new RuntimeException("No parts found in content.");
        }

// Extract the "text" field from the first part
        String testCaseContent = (String) parts.get(0).get("text");


        List<String> testCasesList = splitTestCases(testCaseContent);

        List<Map<String, String>> outputList = new ArrayList<>();

        for (String testCase : testCasesList) {
            // Split the test case content into lines
            String[] lines = testCase.split("\n");
            StringBuilder testCaseContentBuilder = new StringBuilder();

            // Extract ChangeType and ID from the last lines
            String changeType = "";
            String id = "";

            if (lines[lines.length - 1].startsWith("- ID:")) {
                id = lines[lines.length - 1].replace("- ID:", "").trim();
                changeType = lines[lines.length - 2].replace("- ChangeType:", "").trim();
            } else {
                changeType = lines[lines.length - 1].replace("- ChangeType:", "").trim();
            }

            // Rebuild the content without the last two lines (ChangeType and ID)
            for (int i = 0; i < lines.length - (id.isEmpty() ? 1 : 2); i++) {
                testCaseContentBuilder.append(lines[i]).append("\n");
            }
            String updatedTestCaseContent = testCaseContentBuilder.toString().trim();

            // Handle the ChangeType cases
            if ("UPDATED".equals(changeType)) {
                final String finalId = id;
                String oldTestCaseContent = existingTestCases.stream()
                        .filter(tc -> String.valueOf(tc.getId()).equals(finalId))
                        .findFirst()
                        .map(ContractTestCase::getTestCaseContent)
                        .orElse("");

                Map<String, String> jsonEntry = new HashMap<>();
                jsonEntry.put("id", id);
                jsonEntry.put("tc_old", oldTestCaseContent);
                jsonEntry.put("tc_new", updatedTestCaseContent);
                outputList.add(jsonEntry);

            } else if ("ADDED".equals(changeType)) {
                Map<String, String> jsonEntry = new HashMap<>();
                jsonEntry.put("id", "");
                jsonEntry.put("tc_old", "");
                jsonEntry.put("tc_new", updatedTestCaseContent);
                outputList.add(jsonEntry);

            } else if ("DELETED".equals(changeType)) {
                final String finalId = id;
                String oldTestCaseContent = existingTestCases.stream()
                        .filter(tc -> String.valueOf(tc.getId()).equals(finalId))
                        .findFirst()
                        .map(ContractTestCase::getTestCaseContent)
                        .orElse("");

                Map<String, String> jsonEntry = new HashMap<>();
                jsonEntry.put("id", id);
                jsonEntry.put("tc_old", oldTestCaseContent);
                jsonEntry.put("tc_new", "");
                outputList.add(jsonEntry);
            }
            // Skip SAME cases
        }

        return outputList;
    }

    public List<Map<String, String>> updateContractTestCases(String updatedSwaggerFile, String swaggerFileName, String initialSwaggerContent) {
        List<Map<String, String>> newTestCases = generateUpdatedContractTestCases(swaggerFileName, updatedSwaggerFile, initialSwaggerContent);
        return newTestCases;
    }

}
