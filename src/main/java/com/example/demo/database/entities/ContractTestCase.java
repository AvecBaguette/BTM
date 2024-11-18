package com.example.demo.database.entities;

import jakarta.persistence.*;

@Entity
public class ContractTestCase {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private Long id;

    private String fileName;

    private String title;

    private String testRepo;

    @Lob
    private String testCaseContent;

    public ContractTestCase() {}

    public ContractTestCase(String fileName, String testCaseContent, String testCaseTitle) {
        this.fileName = fileName;
        this.testCaseContent = testCaseContent;
        this.title = testCaseTitle;
    }

    public ContractTestCase(String fileName, String testCaseContent, String testCaseTitle, String testRepo) {
        this.fileName = fileName;
        this.testCaseContent = testCaseContent;
        this.title = testCaseTitle;
        this.testRepo = testRepo;
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

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getTestRepo() {
        return testRepo;
    }

    public void setTestRepo(String testRepo) {
        this.testRepo = testRepo;
    }

    public String getTestCaseContent() {
        return testCaseContent;
    }

    public void setTestCaseContent(String testCaseContent) {
        this.testCaseContent = testCaseContent;
    }
}
