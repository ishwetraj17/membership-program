package com.firstclub.merchant.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.firstclub.merchant.auth.dto.MerchantApiKeyCreateRequestDTO;
import com.firstclub.merchant.auth.dto.MerchantApiKeyCreateResponseDTO;
import com.firstclub.merchant.auth.dto.MerchantApiKeyResponseDTO;
import com.firstclub.merchant.auth.entity.MerchantApiKey;
import com.firstclub.merchant.auth.entity.MerchantApiKeyMode;
import com.firstclub.merchant.auth.entity.MerchantApiKeyStatus;
import com.firstclub.merchant.auth.repository.MerchantApiKeyRepository;
import com.firstclub.merchant.auth.service.impl.MerchantApiKeyServiceImpl;
import com.firstclub.merchant.entity.MerchantAccount;
import com.firstclub.merchant.entity.MerchantStatus;
import com.firstclub.merchant.repository.MerchantAccountRepository;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("MerchantApiKeyServiceImpl Unit Tests")
class MerchantApiKeyServiceTest {

    @Mock private MerchantApiKeyRepository repository;
    @Mock private MerchantAccountRepository merchantAccountRepository;
    @Spy  private ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks
    private MerchantApiKeyServiceImpl service;

    @Captor
    private ArgumentCaptor<MerchantApiKey> keyCaptor;

    private static final Long MERCHANT_ID = 1L;

    private MerchantAccount merchant;

    @BeforeEach
    void setUp() {
        merchant = MerchantAccount.builder()
                .id(MERCHANT_ID).merchantCode("TEST").legalName("Test Ltd")
                .status(MerchantStatus.ACTIVE).build();
    }

    private MerchantApiKeyCreateRequestDTO createRequest() {
        return MerchantApiKeyCreateRequestDTO.builder()
                .mode(MerchantApiKeyMode.SANDBOX)
                .scopes(List.of("customers:read", "payments:read"))
                .build();
    }

    // ── CreateApiKey ──────────────────────────────────────────────────────────

    @Nested
    @DisplayName("createApiKey")
    class CreateApiKeyTests {

        @Test
        @DisplayName("returns a raw key prefixed fc_sb_ for sandbox mode")
        void createApiKey_sandboxMode_rawKeyHasCorrectPrefix() {
            when(merchantAccountRepository.findById(MERCHANT_ID)).thenReturn(Optional.of(merchant));
            when(repository.save(any())).thenAnswer(inv -> {
                MerchantApiKey k = inv.getArgument(0);
                k.setId(1L);
                return k;
            });

            MerchantApiKeyCreateResponseDTO resp = service.createApiKey(MERCHANT_ID, createRequest());

            assertThat(resp.rawKey()).isNotNull();
            assertThat(resp.rawKey()).startsWith("fc_sb_");
            assertThat(resp.mode()).isEqualTo(MerchantApiKeyMode.SANDBOX);
            assertThat(resp.status()).isEqualTo(MerchantApiKeyStatus.ACTIVE);
            assertThat(resp.scopes()).containsExactlyInAnyOrder("customers:read", "payments:read");
        }

        @Test
        @DisplayName("returns a raw key prefixed fc_lv_ for live mode")
        void createApiKey_liveMode_rawKeyHasCorrectPrefix() {
            when(merchantAccountRepository.findById(MERCHANT_ID)).thenReturn(Optional.of(merchant));
            when(repository.save(any())).thenAnswer(inv -> {
                MerchantApiKey k = inv.getArgument(0);
                k.setId(2L);
                return k;
            });

            MerchantApiKeyCreateRequestDTO liveReq = MerchantApiKeyCreateRequestDTO.builder()
                    .mode(MerchantApiKeyMode.LIVE).scopes(List.of("payments:write")).build();

            MerchantApiKeyCreateResponseDTO resp = service.createApiKey(MERCHANT_ID, liveReq);

            assertThat(resp.rawKey()).startsWith("fc_lv_");
        }

        @Test
        @DisplayName("stored hash is SHA-256 of raw key — raw key is NOT stored")
        void createApiKey_storedHashIsCorrectAndNotPlaintext() {
            when(merchantAccountRepository.findById(MERCHANT_ID)).thenReturn(Optional.of(merchant));
            when(repository.save(any())).thenAnswer(inv -> {
                MerchantApiKey k = inv.getArgument(0);
                k.setId(1L);
                return k;
            });

            MerchantApiKeyCreateResponseDTO resp = service.createApiKey(MERCHANT_ID, createRequest());

            verify(repository).save(keyCaptor.capture());
            MerchantApiKey saved = keyCaptor.getValue();

            // Raw key must NOT be stored
            assertThat(saved.getKeyHash()).isNotEqualTo(resp.rawKey());
            // Hash must be 64 hex chars (SHA-256 = 32 bytes = 64 hex)
            assertThat(saved.getKeyHash()).hasSize(64);
            // Stored hash must equal SHA-256(rawKey)
            assertThat(saved.getKeyHash()).isEqualTo(sha256(resp.rawKey()));
            // Prefix must equal the first PREFIX_LENGTH chars of rawKey
            assertThat(saved.getKeyPrefix()).isEqualTo(resp.rawKey().substring(0, MerchantApiKeyServiceImpl.PREFIX_LENGTH));
        }

        @Test
        @DisplayName("404 when merchant does not exist")
        void createApiKey_merchantNotFound_throws404() {
            when(merchantAccountRepository.findById(MERCHANT_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.createApiKey(MERCHANT_ID, createRequest()))
                    .isInstanceOf(ResponseStatusException.class)
                    .hasMessageContaining("Merchant not found");
        }

        @Test
        @DisplayName("two created keys have different raw keys and hashes")
        void createApiKey_uniqueKeysEachTime() {
            when(merchantAccountRepository.findById(MERCHANT_ID)).thenReturn(Optional.of(merchant));
            when(repository.save(any())).thenAnswer(inv -> {
                MerchantApiKey k = inv.getArgument(0);
                k.setId((long)(Math.random() * 1000));
                return k;
            });

            MerchantApiKeyCreateResponseDTO r1 = service.createApiKey(MERCHANT_ID, createRequest());
            MerchantApiKeyCreateResponseDTO r2 = service.createApiKey(MERCHANT_ID, createRequest());

            assertThat(r1.rawKey()).isNotEqualTo(r2.rawKey());
        }
    }

    // ── ListApiKeys ───────────────────────────────────────────────────────────

    @Nested
    @DisplayName("listApiKeys")
    class ListApiKeyTests {

        @Test
        @DisplayName("returns mapped list for merchant")
        void listApiKeys_returnsMappedList() {
            MerchantApiKey key = MerchantApiKey.builder()
                    .id(1L).merchantId(MERCHANT_ID).keyPrefix("fc_sb_aabbccdd11223344")
                    .keyHash("somehash").mode(MerchantApiKeyMode.SANDBOX)
                    .scopesJson("[\"customers:read\"]").status(MerchantApiKeyStatus.ACTIVE).build();

            when(repository.findByMerchantId(MERCHANT_ID)).thenReturn(List.of(key));

            List<MerchantApiKeyResponseDTO> result = service.listApiKeys(MERCHANT_ID);

            assertThat(result).hasSize(1);
            assertThat(result.get(0).keyPrefix()).isEqualTo("fc_sb_aabbccdd11223344");
            assertThat(result.get(0).scopes()).containsExactly("customers:read");
        }

        @Test
        @DisplayName("returns empty list when no keys exist")
        void listApiKeys_empty() {
            when(repository.findByMerchantId(MERCHANT_ID)).thenReturn(List.of());

            assertThat(service.listApiKeys(MERCHANT_ID)).isEmpty();
        }
    }

    // ── RevokeApiKey ──────────────────────────────────────────────────────────

    @Nested
    @DisplayName("revokeApiKey")
    class RevokeApiKeyTests {

        @Test
        @DisplayName("revoke sets status to REVOKED and saves")
        void revokeApiKey_setsStatusRevoked() {
            MerchantApiKey key = MerchantApiKey.builder()
                    .id(1L).merchantId(MERCHANT_ID).keyPrefix("fc_sb_xx")
                    .keyHash("hash").mode(MerchantApiKeyMode.SANDBOX)
                    .scopesJson("[]").status(MerchantApiKeyStatus.ACTIVE).build();
            when(repository.findByMerchantIdAndId(MERCHANT_ID, 1L)).thenReturn(Optional.of(key));
            when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            service.revokeApiKey(MERCHANT_ID, 1L);

            verify(repository).save(keyCaptor.capture());
            assertThat(keyCaptor.getValue().getStatus()).isEqualTo(MerchantApiKeyStatus.REVOKED);
        }

        @Test
        @DisplayName("404 when key not found for merchant")
        void revokeApiKey_notFound_throws404() {
            when(repository.findByMerchantIdAndId(MERCHANT_ID, 99L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.revokeApiKey(MERCHANT_ID, 99L))
                    .isInstanceOf(ResponseStatusException.class)
                    .hasMessageContaining("API key not found");
        }
    }

    // ── AuthenticateApiKey ────────────────────────────────────────────────────

    @Nested
    @DisplayName("authenticateApiKey")
    class AuthenticateApiKeyTests {

        private MerchantApiKey keyForRaw(String rawKey) {
            String prefix = rawKey.substring(0, MerchantApiKeyServiceImpl.PREFIX_LENGTH);
            return MerchantApiKey.builder()
                    .id(1L).merchantId(MERCHANT_ID).keyPrefix(prefix)
                    .keyHash(sha256(rawKey)).mode(MerchantApiKeyMode.SANDBOX)
                    .scopesJson("[\"customers:read\"]").status(MerchantApiKeyStatus.ACTIVE).build();
        }

        @Test
        @DisplayName("valid key + correct hash → returns present")
        void authenticateApiKey_validKey_returnsPresent() {
            // Build a realistic-format key: fc_sb_{16hex}_{40hex}
            String rawKey = "fc_sb_aabbccdd11223344_aabbccddaabbccddaabbccddaabbccddaabbccdd11";
            MerchantApiKey key = keyForRaw(rawKey);
            when(repository.findByKeyPrefix(rawKey.substring(0, MerchantApiKeyServiceImpl.PREFIX_LENGTH)))
                    .thenReturn(Optional.of(key));

            Optional<MerchantApiKey> result = service.authenticateApiKey(rawKey);

            assertThat(result).isPresent();
            assertThat(result.get().getMerchantId()).isEqualTo(MERCHANT_ID);
        }

        @Test
        @DisplayName("revoked key → returns empty")
        void authenticateApiKey_revokedKey_returnsEmpty() {
            String rawKey = "fc_sb_aabbccdd11223344_aabbccddaabbccddaabbccddaabbccddaabbccdd11";
            MerchantApiKey key = keyForRaw(rawKey);
            key.setStatus(MerchantApiKeyStatus.REVOKED);
            when(repository.findByKeyPrefix(rawKey.substring(0, MerchantApiKeyServiceImpl.PREFIX_LENGTH)))
                    .thenReturn(Optional.of(key));

            assertThat(service.authenticateApiKey(rawKey)).isEmpty();
        }

        @Test
        @DisplayName("wrong hash → returns empty")
        void authenticateApiKey_wrongHash_returnsEmpty() {
            String rawKey      = "fc_sb_aabbccdd11223344_aabbccddaabbccddaabbccddaabbccddaabbccdd11";
            String tampered    = "fc_sb_aabbccdd11223344_xxbbccddaabbccddaabbccddaabbccddaabbccdd11";
            MerchantApiKey key = keyForRaw(rawKey); // hash based on rawKey
            when(repository.findByKeyPrefix(tampered.substring(0, MerchantApiKeyServiceImpl.PREFIX_LENGTH)))
                    .thenReturn(Optional.of(key));

            // Send tampered key — hash will not match
            assertThat(service.authenticateApiKey(tampered)).isEmpty();
        }

        @Test
        @DisplayName("unknown prefix → returns empty (no lookup hit)")
        void authenticateApiKey_unknownPrefix_returnsEmpty() {
            String rawKey = "fc_sb_0000000000000000_aabbccddaabbccddaabbccddaabbccddaabbccdd11";
            when(repository.findByKeyPrefix(rawKey.substring(0, MerchantApiKeyServiceImpl.PREFIX_LENGTH)))
                    .thenReturn(Optional.empty());

            assertThat(service.authenticateApiKey(rawKey)).isEmpty();
        }

        @Test
        @DisplayName("key shorter than PREFIX_LENGTH → returns empty without lookup")
        void authenticateApiKey_shortKey_returnsEmpty() {
            assertThat(service.authenticateApiKey("fc_sb_tooshort")).isEmpty();
            verifyNoInteractions(repository);
        }

        @Test
        @DisplayName("null key → returns empty without lookup")
        void authenticateApiKey_null_returnsEmpty() {
            assertThat(service.authenticateApiKey(null)).isEmpty();
            verifyNoInteractions(repository);
        }
    }

    // ── Test helper ───────────────────────────────────────────────────────────

    private static String sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }
}
