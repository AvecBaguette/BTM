package com.example.demo.database.repositories;
import java.util.List;
import java.util.Optional;

import com.example.demo.database.entities.TestCase;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TestCaseRepository extends JpaRepository<TestCase, Long> {
    Optional<List<TestCase>> findByFileName(String fileName);
}
