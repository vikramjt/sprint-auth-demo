package org.example.authserver.config;

import org.springframework.ldap.core.AttributesMapper;
import org.springframework.ldap.core.LdapTemplate;
import org.springframework.ldap.core.support.BaseLdapPathContextSource;
import org.springframework.ldap.filter.EqualsFilter;
import org.springframework.ldap.support.LdapNameBuilder;
import org.springframework.web.bind.annotation.*;

import javax.naming.Name;
import javax.naming.directory.*;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * REST endpoints for managing OpenLDAP users.
 * Used during development / testing. Should be disabled in production.
 */
@RestController
@RequestMapping("/ldap/users")
public class LdapUserManagementController {

    private static final String PEOPLE_OU = "ou=people";
    private static final String OBJECT_CLASS = "objectClass";

    private final LdapTemplate ldapTemplate;
    private final LdapAuthProperties ldapProperties;

    public LdapUserManagementController(BaseLdapPathContextSource contextSource,
                                        LdapAuthProperties ldapProperties) {
        this.ldapTemplate = new LdapTemplate(contextSource);
        this.ldapProperties = ldapProperties;
    }

    /**
     * GET /ldap/users - List all users
     */
    @GetMapping
    public Map<String, Object> listUsers() {
        Map<String, Object> result = new HashMap<>();
        try {
            List<Map<String, String>> users = ldapTemplate.search(
                    PEOPLE_OU,
                    "(objectClass=inetOrgPerson)",
                    (AttributesMapper<Map<String, String>>) attrs -> {
                        Map<String, String> user = new HashMap<>();
                        try {
                            user.put("uid", getAttr(attrs, "uid"));
                            user.put("cn", getAttr(attrs, "cn"));
                            user.put("sn", getAttr(attrs, "sn"));
                            user.put("mail", getAttr(attrs, "mail"));
                        } catch (Exception e) { /* skip */ }
                        return user;
                    });

            result.put("status", "SUCCESS");
            result.put("users", users);
            result.put("count", users.size());
        } catch (Exception e) {
            result.put("status", "FAILED");
            result.put("error", e.getMessage());
        }
        return result;
    }

    /**
     * POST /ldap/users - Create a new user
     * Body: { "uid":"john", "cn":"John Doe", "sn":"Doe", "mail":"john@example.org", "password":"secret" }
     */
    @PostMapping
    public Map<String, Object> createUser(@RequestBody CreateUserRequest request) {
        Map<String, Object> result = new HashMap<>();
        try {
            Name dn = buildUserDn(request.uid());

            BasicAttributes attrs = new BasicAttributes();
            BasicAttribute objectClass = new BasicAttribute(OBJECT_CLASS);
            objectClass.add("top");
            objectClass.add("person");
            objectClass.add("organizationalPerson");
            objectClass.add("inetOrgPerson");
            attrs.put(objectClass);
            attrs.put("uid", request.uid());
            attrs.put("cn", request.cn());
            attrs.put("sn", request.sn());
            if (request.mail() != null) attrs.put("mail", request.mail());
            if (request.telephoneNumber() != null) attrs.put("telephoneNumber", request.telephoneNumber());
            attrs.put("userPassword", request.password());

            ldapTemplate.bind(dn, null, attrs);

            result.put("status", "SUCCESS");
            result.put("message", "User created: " + request.uid());
            result.put("dn", dn.toString());
        } catch (Exception e) {
            result.put("status", "FAILED");
            result.put("error", e.getMessage());
        }
        return result;
    }

    /**
     * GET /ldap/users/{uid} - Get a single user
     */
    @GetMapping("/{uid}")
    public Map<String, Object> getUser(@PathVariable String uid) {
        Map<String, Object> result = new HashMap<>();
        try {
            List<Map<String, String>> users = ldapTemplate.search(
                    PEOPLE_OU,
                    new EqualsFilter("uid", uid).encode(),
                    (AttributesMapper<Map<String, String>>) attrs -> {
                        Map<String, String> user = new HashMap<>();
                        try {
                            user.put("uid", getAttr(attrs, "uid"));
                            user.put("cn", getAttr(attrs, "cn"));
                            user.put("sn", getAttr(attrs, "sn"));
                            user.put("mail", getAttr(attrs, "mail"));
                            user.put("telephoneNumber", getAttr(attrs, "telephoneNumber"));
                        } catch (Exception e) { /* skip */ }
                        return user;
                    });

            if (users.isEmpty()) {
                result.put("status", "NOT_FOUND");
                result.put("message", "User not found: " + uid);
            } else {
                result.put("status", "SUCCESS");
                result.put("user", users.get(0));
            }
        } catch (Exception e) {
            result.put("status", "FAILED");
            result.put("error", e.getMessage());
        }
        return result;
    }

    /**
     * PUT /ldap/users/{uid}/password - Change password
     * Body: { "newPassword": "secret123" }
     */
    @PutMapping("/{uid}/password")
    public Map<String, Object> changePassword(@PathVariable String uid,
                                              @RequestBody ChangePasswordRequest request) {
        Map<String, Object> result = new HashMap<>();
        try {
            Name dn = buildUserDn(uid);

            ModificationItem[] mods = new ModificationItem[]{
                    new ModificationItem(DirContext.REPLACE_ATTRIBUTE,
                            new BasicAttribute("userPassword", request.newPassword()))
            };
            ldapTemplate.modifyAttributes(dn, mods);

            result.put("status", "SUCCESS");
            result.put("message", "Password changed for: " + uid);
        } catch (Exception e) {
            result.put("status", "FAILED");
            result.put("error", e.getMessage());
        }
        return result;
    }

    /**
     * DELETE /ldap/users/{uid} - Delete a user
     */
    @DeleteMapping("/{uid}")
    public Map<String, Object> deleteUser(@PathVariable String uid) {
        Map<String, Object> result = new HashMap<>();
        try {
            Name dn = buildUserDn(uid);
            ldapTemplate.unbind(dn);

            result.put("status", "SUCCESS");
            result.put("message", "User deleted: " + uid);
        } catch (Exception e) {
            result.put("status", "FAILED");
            result.put("error", e.getMessage());
        }
        return result;
    }

    // ── helpers ──────────────────────────────────────────────

    private Name buildUserDn(String uid) {
        return LdapNameBuilder.newInstance(PEOPLE_OU)
                .add("uid", uid)
                .build();
    }

    private String getAttr(Attributes attrs, String name) throws Exception {
        Attribute attr = attrs.get(name);
        return attr != null ? (String) attr.get() : null;
    }

    // ── request records ──────────────────────────────────────

    public record CreateUserRequest(String uid, String cn, String sn,
                                    String mail, String telephoneNumber, String password) {}

    public record ChangePasswordRequest(String newPassword) {}
}




