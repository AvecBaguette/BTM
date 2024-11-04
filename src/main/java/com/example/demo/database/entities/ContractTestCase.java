package com.example.demo.database.entities;

import jakarta.persistence.*;

@Entity
public class ContractTestCase {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private Long id;

    private String fileName;

    @Lob
    private String testCaseContent;

    public ContractTestCase() {}

    public ContractTestCase(String fileName, String testCaseContent) {
        this.fileName = fileName;
        this.testCaseContent = testCaseContent;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getFileName() {
        return fileName;
    }

    public void setFileName(String fileName) {
        this.fileName = fileName;
    }

    public String getTestCaseContent() {
        return testCaseContent;
    }

    public void setTestCaseContent(String testCaseContent) {
        this.testCaseContent = testCaseContent;
    }
}
