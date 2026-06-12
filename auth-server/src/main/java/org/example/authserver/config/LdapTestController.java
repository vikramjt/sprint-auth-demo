package org.example.authserver.config;

import org.springframework.ldap.core.DirContextOperations;
import org.springframework.ldap.core.AttributesMapper;
import org.springframework.ldap.core.LdapTemplate;
import org.springframework.ldap.core.support.BaseLdapPathContextSource;
import org.springframework.ldap.filter.AndFilter;
import org.springframework.ldap.filter.EqualsFilter;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.naming.NamingException;
import javax.naming.directory.Attribute;

/**
 * REST endpoints for testing LDAP connectivity.
 * Useful for debugging configuration without running full auth flow.
 */
@RestController
public class LdapTestController {

    private final BaseLdapPathContextSource contextSource;
    private final LdapAuthProperties ldapProperties;

    public LdapTestController(BaseLdapPathContextSource contextSource, LdapAuthProperties ldapProperties) {
        this.contextSource = contextSource;
        this.ldapProperties = ldapProperties;
    }

    /**
     * GET /ldap/test - Test basic LDAP connection
     */
    @GetMapping("/ldap/test")
    public Map<String, Object> testConnection() {
        Map<String, Object> result = new HashMap<>();
        try {
            contextSource.getReadOnlyContext();
            result.put("status", "SUCCESS");
            result.put("message", "LDAP connection successful");
            result.put("ldapUrl", ldapProperties.getUrl());
            result.put("baseDn", ldapProperties.getBaseDn());
            return result;
        } catch (Exception e) {
            result.put("status", "FAILED");
            result.put("message", e.getMessage());
            result.put("error", e.getClass().getName());
            return result;
        }
    }

    /**
     * POST /ldap/test-auth - Test LDAP user authentication
     * Usage: curl -X POST http://localhost:9000/ldap/test-auth?username=user1&password=password1
     */
    @PostMapping("/ldap/test-auth")
    public Map<String, Object> testAuthentication(
            @RequestParam String username,
            @RequestParam String password) {
        Map<String, Object> result = new HashMap<>();
        try {
            LdapTemplate ldapTemplate = new LdapTemplate(contextSource);

            // Build the user DN
            String userDnPattern = ldapProperties.getUserDnPattern();
            String userDn = String.format(userDnPattern, username);

            // Try to bind as the user
            contextSource.getContext(userDn, password);

            result.put("status", "SUCCESS");
            result.put("message", "User authentication successful");
            result.put("username", username);
            result.put("userDn", userDn);

            return result;
        } catch (Exception e) {
            result.put("status", "FAILED");
            result.put("message", "Authentication failed: " + e.getMessage());
            result.put("error", e.getClass().getName());
            return result;
        }
    }

    /**
     * GET /ldap/search - Search LDAP for users by attribute
     * Usage: curl http://localhost:9000/ldap/search?uid=user1
     */
    @GetMapping("/ldap/search")
    public Map<String, Object> searchUsers(@RequestParam String uid) {
        Map<String, Object> result = new HashMap<>();
        try {
            LdapTemplate ldapTemplate = new LdapTemplate(contextSource);

            // Search for user
            AndFilter filter = new AndFilter();
            filter.and(new EqualsFilter("objectClass", "inetOrgPerson"))
                    .and(new EqualsFilter("uid", uid));

            List<Map<String, String>> users = ldapTemplate.search(
                    ldapProperties.getBaseDn(),
                    filter.encode(),
                    (AttributesMapper<Map<String, String>>) attrs -> {
                        Map<String, String> user = new HashMap<>();
                        try {
                            user.put("uid", (String) attrs.get("uid").get());
                            user.put("cn", (String) attrs.get("cn").get());
                            Attribute mailAttr = attrs.get("mail");
                            if (mailAttr != null) {
                                user.put("mail", (String) mailAttr.get());
                            }
                        } catch (NamingException e) {
                            // Skip attribute if not found
                        }
                        return user;
                    });

            result.put("status", "SUCCESS");
            result.put("searchedFor", uid);
            result.put("results", users);
            result.put("count", users.size());

            return result;
        } catch (Exception e) {
            result.put("status", "FAILED");
            result.put("message", "Search failed: " + e.getMessage());
            result.put("error", e.getClass().getName());
            return result;
        }
    }

    /**
     * GET /ldap/config - Show LDAP configuration (for debugging)
     */
    @GetMapping("/ldap/config")
    public Map<String, String> showConfig() {
        Map<String, String> config = new HashMap<>();
        config.put("ldapUrl", ldapProperties.getUrl());
        config.put("baseDn", ldapProperties.getBaseDn());
        config.put("userDnPattern", ldapProperties.getUserDnPattern());
        config.put("managerDn", ldapProperties.getManagerDn() != null ? "***set***" : "not-set");
        return config;
    }
}

