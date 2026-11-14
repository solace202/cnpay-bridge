package dev.cnpay.idempotency;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.cnpay.web.ApiException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.function.Supplier;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Stripe-style Idempotency-Key handling for POSTs that create money movements. The key row is claimed
 * before the work starts, so two concurrent retries cannot both reach the provider.
 * Same key + same body replays the stored response; same key + different body is a client bug (422).
 */
@Service
public class IdempotencyService {

    private final JdbcClient jdbc;
    private final ObjectMapper mapper;

    public IdempotencyService(JdbcClient jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public ResponseEntity<?> run(String scope, String key, Object request, Supplier<ResponseEntity<?>> action) {
        if (key == null || key.isBlank()) {
            return action.get();
        }
        if (key.length() > 80) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "Idempotency-Key is longer than 80 characters");
        }
        String hash = sha256(json(request));
        try {
            jdbc.sql("insert into idempotency_keys (idem_key, scope, request_hash) values (?, ?, ?)")
                    .params(key, scope, hash).update();
        } catch (DuplicateKeyException e) {
            return replay(scope, key, hash);
        }
        try {
            ResponseEntity<?> response = action.get();
            jdbc.sql("update idempotency_keys set response_code = ?, response_body = ? where scope = ? and idem_key = ?")
                    .params(response.getStatusCode().value(), json(response.getBody()), scope, key).update();
            return response;
        } catch (RuntimeException e) {
            // Nothing was committed on our side; release the key so the client can retry.
            jdbc.sql("delete from idempotency_keys where scope = ? and idem_key = ?").params(scope, key).update();
            throw e;
        }
    }

    private ResponseEntity<?> replay(String scope, String key, String hash) {
        Map<String, Object> row = jdbc.sql("""
                select request_hash, response_code, cast(response_body as char) as body
                  from idempotency_keys where scope = ? and idem_key = ?
                """).params(scope, key).query().singleRow();
        if (!hash.equals(row.get("request_hash"))) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "IDEMPOTENCY_KEY_REUSED",
                    "Idempotency-Key was already used with a different request body");
        }
        if (row.get("response_code") == null) {
            throw new ApiException(HttpStatus.CONFLICT, "IDEMPOTENCY_IN_PROGRESS",
                    "A request with this Idempotency-Key is still being processed");
        }
        try {
            Object body = mapper.readTree((String) row.get("body"));
            return ResponseEntity.status(((Number) row.get("response_code")).intValue())
                    .header("Idempotent-Replayed", "true").body(body);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
