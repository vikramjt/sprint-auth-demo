package org.example.authserver;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {
        "app.ldap.url=ldap://localhost:389",
        "app.ldap.base-dn=dc=example,dc=org",
        "app.ldap.user-dn-pattern=uid={0},ou=people"
})
class AuthServerApplicationTests {

    @Test
    void contextLoads() {
    }
}

