package com.bank.rcp.web;

import com.bank.rcp.data.DataStore;
import com.bank.rcp.model.Models.User;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.*;

/** Error mapping and simple human-role checks (corporate IdP/OIDC in production; X-User header in the POC). */
@RestControllerAdvice
public class ApiSupport {

    public static User requireRole(DataStore store, String userId, String... roles) {
        User u = store.users.get(userId);
        if (u == null) throw new SecurityException("Unknown user " + userId);
        if (roles.length == 0) return u;
        for (String r : roles) if (u.roles.contains(r)) return u;
        throw new SecurityException(userId + " lacks role " + Arrays.toString(roles));
    }

    @ExceptionHandler(SecurityException.class)
    public ResponseEntity<Map<String, Object>> forbidden(SecurityException e) { return err(HttpStatus.FORBIDDEN, e); }

    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<Map<String, Object>> notFound(NoSuchElementException e) { return err(HttpStatus.NOT_FOUND, e); }

    @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class})
    public ResponseEntity<Map<String, Object>> bad(RuntimeException e) { return err(HttpStatus.BAD_REQUEST, e); }

    private static ResponseEntity<Map<String, Object>> err(HttpStatus s, Exception e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("error", s.getReasonPhrase());
        m.put("message", e.getMessage());
        return ResponseEntity.status(s).body(m);
    }
}
