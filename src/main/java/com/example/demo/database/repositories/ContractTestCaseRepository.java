package com.example.demo.database.repositories;
import java.util.List;
import java.util.Optional;
import com.example.demo.database.entities.ContractTestCase;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ContractTestCaseRepository extends JpaRepository<ContractTestCase, Long> {
    List<ContractTestCase> findByFileName(String fileName);
    Optional<ContractTestCase> findByFileNameAndTestCaseContent(String fileName, String testCaseContent);
    List<ContractTestCase> findByTestRepo(String testRepo);
}
