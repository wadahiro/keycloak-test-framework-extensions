package io.github.wadahiro.keycloak.testframework.quarkus;

import org.keycloak.testframework.ui.webdriver.ManagedWebDriver;
import org.openqa.selenium.By;
import org.openqa.selenium.TimeoutException;
import org.openqa.selenium.WebDriver;

final class BrowserPageAssertions {
    private BrowserPageAssertions() {}

    static void waitForLoginForm(ManagedWebDriver browser) {
        waitForElements(browser, By.id("username"), By.id("password"));
    }

    static void waitForElement(ManagedWebDriver browser, By selector) {
        waitForElements(browser, selector);
    }

    static void waitForUrlToLeave(ManagedWebDriver browser, String path) {
        try {
            browser.waiting().until(driver -> !driver.getCurrentUrl().contains(path));
        } catch (TimeoutException e) {
            throw new AssertionError("Expected browser to leave " + path + " at " + browser.getCurrentUrl() + "\n"
                    + browser.page().getPageSource(), e);
        }
    }

    private static void waitForElements(ManagedWebDriver browser, By... selectors) {
        try {
            browser.waiting().until(driver -> allPresent(driver, selectors));
        } catch (TimeoutException e) {
            throw new AssertionError("Expected browser elements " + String.join(", ",
                    java.util.Arrays.stream(selectors).map(By::toString).toList()) + " at " + browser.getCurrentUrl()
                    + "\n" + browser.page().getPageSource(), e);
        }
    }

    private static boolean allPresent(WebDriver driver, By[] selectors) {
        for (By selector : selectors) {
            if (driver.findElements(selector).isEmpty()) return false;
        }
        return true;
    }
}
