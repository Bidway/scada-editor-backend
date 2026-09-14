package com.example.editor.repository.automation;

import com.example.editor.model.automation.AutomationWatchdog;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AutomationWatchdogRepository extends JpaRepository<AutomationWatchdog, Long> {
}
