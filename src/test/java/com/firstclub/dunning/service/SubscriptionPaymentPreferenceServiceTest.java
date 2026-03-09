package com.firstclub.dunning.service;

import com.firstclub.dunning.dto.SubscriptionPaymentPreferenceRequestDTO;
import com.firstclub.dunning.dto.SubscriptionPaymentPreferenceResponseDTO;
import com.firstclub.dunning.entity.SubscriptionPaymentPreference;
import com.firstclub.dunning.repository.SubscriptionPaymentPreferenceRepository;
import com.firstclub.dunning.service.impl.SubscriptionPaymentPreferenceServiceImpl;
import com.firstclub.membership.exception.MembershipException;
import com.firstclub.payments.entity.PaymentMethod;
import com.firstclub.payments.entity.PaymentMethodStatus;
import com.firstclub.payments.repository.PaymentMethodRepository;
import com.firstclub.subscription.repository.SubscriptionV2Repository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("SubscriptionPaymentPreferenceService Unit Tests")
class SubscriptionPaymentPreferenceServiceTest {

    @Mock private SubscriptionPaymentPreferenceRepository preferenceRepository;
    @Mock private SubscriptionV2Repository                subscriptionV2Repository;
    @Mock private PaymentMethodRepository                 paymentMethodRepository;

    @InjectMocks
    private SubscriptionPaymentPreferenceServiceImpl preferenceService;

    private static final Long MERCHANT_ID      = 1L;
    private static final Long SUBSCRIPTION_ID  = 10L;
    private static final Long CUSTOMER_ID      = 20L;
    private static final Long PRIMARY_PM_ID    = 100L;
    private static final Long BACKUP_PM_ID     = 200L;

    private PaymentMethod activePrimaryPm;
    private PaymentMethod activeBackupPm;

    @BeforeEach
    void setUp() {
        activePrimaryPm = PaymentMethod.builder().id(PRIMARY_PM_ID)
                .status(PaymentMethodStatus.ACTIVE).build();
        activeBackupPm = PaymentMethod.builder().id(BACKUP_PM_ID)
                .status(PaymentMethodStatus.ACTIVE).build();
    }

    // ── setPaymentPreferences ─────────────────────────────────────────────────

    @Nested
    @DisplayName("setPaymentPreferences")
    class SetPreferences {

        @Test
        @DisplayName("valid primary PM only → created and DTO returned")
        void setPreferences_valid_primaryOnly_success() {
            when(subscriptionV2Repository.findCustomerIdByMerchantIdAndId(MERCHANT_ID, SUBSCRIPTION_ID))
                    .thenReturn(Optional.of(CUSTOMER_ID));
            when(paymentMethodRepository.findByMerchantIdAndCustomerIdAndId(MERCHANT_ID, CUSTOMER_ID, PRIMARY_PM_ID))
                    .thenReturn(Optional.of(activePrimaryPm));
            when(preferenceRepository.findBySubscriptionId(SUBSCRIPTION_ID))
                    .thenReturn(Optional.empty());
            SubscriptionPaymentPreference saved = SubscriptionPaymentPreference.builder()
                    .id(1L).subscriptionId(SUBSCRIPTION_ID)
                    .primaryPaymentMethodId(PRIMARY_PM_ID).build();
            when(preferenceRepository.save(any())).thenReturn(saved);

            SubscriptionPaymentPreferenceRequestDTO req =
                    SubscriptionPaymentPreferenceRequestDTO.builder()
                            .primaryPaymentMethodId(PRIMARY_PM_ID).build();

            SubscriptionPaymentPreferenceResponseDTO result =
                    preferenceService.setPaymentPreferences(MERCHANT_ID, SUBSCRIPTION_ID, req);

            assertThat(result.getId()).isEqualTo(1L);
            assertThat(result.getPrimaryPaymentMethodId()).isEqualTo(PRIMARY_PM_ID);
        }

        @Test
        @DisplayName("valid primary + backup PM → both saved")
        void setPreferences_valid_withBackup_success() {
            when(subscriptionV2Repository.findCustomerIdByMerchantIdAndId(MERCHANT_ID, SUBSCRIPTION_ID))
                    .thenReturn(Optional.of(CUSTOMER_ID));
            when(paymentMethodRepository.findByMerchantIdAndCustomerIdAndId(MERCHANT_ID, CUSTOMER_ID, PRIMARY_PM_ID))
                    .thenReturn(Optional.of(activePrimaryPm));
            when(paymentMethodRepository.findByMerchantIdAndCustomerIdAndId(MERCHANT_ID, CUSTOMER_ID, BACKUP_PM_ID))
                    .thenReturn(Optional.of(activeBackupPm));
            when(preferenceRepository.findBySubscriptionId(SUBSCRIPTION_ID))
                    .thenReturn(Optional.empty());
            SubscriptionPaymentPreference saved = SubscriptionPaymentPreference.builder()
                    .id(1L).subscriptionId(SUBSCRIPTION_ID)
                    .primaryPaymentMethodId(PRIMARY_PM_ID)
                    .backupPaymentMethodId(BACKUP_PM_ID).build();
            when(preferenceRepository.save(any())).thenReturn(saved);

            SubscriptionPaymentPreferenceRequestDTO req =
                    SubscriptionPaymentPreferenceRequestDTO.builder()
                            .primaryPaymentMethodId(PRIMARY_PM_ID)
                            .backupPaymentMethodId(BACKUP_PM_ID).build();

            SubscriptionPaymentPreferenceResponseDTO result =
                    preferenceService.setPaymentPreferences(MERCHANT_ID, SUBSCRIPTION_ID, req);

            assertThat(result.getBackupPaymentMethodId()).isEqualTo(BACKUP_PM_ID);
        }

        @Test
        @DisplayName("primary and backup are the same → 422")
        void setPreferences_sameAsPrimary_422() {
            when(subscriptionV2Repository.findCustomerIdByMerchantIdAndId(MERCHANT_ID, SUBSCRIPTION_ID))
                    .thenReturn(Optional.of(CUSTOMER_ID));
            when(paymentMethodRepository.findByMerchantIdAndCustomerIdAndId(MERCHANT_ID, CUSTOMER_ID, PRIMARY_PM_ID))
                    .thenReturn(Optional.of(activePrimaryPm));

            SubscriptionPaymentPreferenceRequestDTO req =
                    SubscriptionPaymentPreferenceRequestDTO.builder()
                            .primaryPaymentMethodId(PRIMARY_PM_ID)
                            .backupPaymentMethodId(PRIMARY_PM_ID).build();

            assertThatThrownBy(() ->
                    preferenceService.setPaymentPreferences(MERCHANT_ID, SUBSCRIPTION_ID, req))
                    .isInstanceOf(MembershipException.class)
                    .hasMessageContaining("different");
        }

        @Test
        @DisplayName("primary PM not owned by merchant/customer → 422")
        void setPreferences_primaryNotOwned_422() {
            when(subscriptionV2Repository.findCustomerIdByMerchantIdAndId(MERCHANT_ID, SUBSCRIPTION_ID))
                    .thenReturn(Optional.of(CUSTOMER_ID));
            when(paymentMethodRepository.findByMerchantIdAndCustomerIdAndId(MERCHANT_ID, CUSTOMER_ID, PRIMARY_PM_ID))
                    .thenReturn(Optional.empty());

            SubscriptionPaymentPreferenceRequestDTO req =
                    SubscriptionPaymentPreferenceRequestDTO.builder()
                            .primaryPaymentMethodId(PRIMARY_PM_ID).build();

            assertThatThrownBy(() ->
                    preferenceService.setPaymentPreferences(MERCHANT_ID, SUBSCRIPTION_ID, req))
                    .isInstanceOf(MembershipException.class)
                    .hasMessageContaining("primaryPaymentMethodId");
        }

        @Test
        @DisplayName("backup PM not owned by merchant/customer → 422")
        void setPreferences_backupNotOwned_422() {
            when(subscriptionV2Repository.findCustomerIdByMerchantIdAndId(MERCHANT_ID, SUBSCRIPTION_ID))
                    .thenReturn(Optional.of(CUSTOMER_ID));
            when(paymentMethodRepository.findByMerchantIdAndCustomerIdAndId(MERCHANT_ID, CUSTOMER_ID, PRIMARY_PM_ID))
                    .thenReturn(Optional.of(activePrimaryPm));
            when(paymentMethodRepository.findByMerchantIdAndCustomerIdAndId(MERCHANT_ID, CUSTOMER_ID, BACKUP_PM_ID))
                    .thenReturn(Optional.empty());

            SubscriptionPaymentPreferenceRequestDTO req =
                    SubscriptionPaymentPreferenceRequestDTO.builder()
                            .primaryPaymentMethodId(PRIMARY_PM_ID)
                            .backupPaymentMethodId(BACKUP_PM_ID).build();

            assertThatThrownBy(() ->
                    preferenceService.setPaymentPreferences(MERCHANT_ID, SUBSCRIPTION_ID, req))
                    .isInstanceOf(MembershipException.class)
                    .hasMessageContaining("backupPaymentMethodId");
        }

        @Test
        @DisplayName("subscription not found → 404")
        void setPreferences_subscriptionNotFound_404() {
            when(subscriptionV2Repository.findCustomerIdByMerchantIdAndId(MERCHANT_ID, SUBSCRIPTION_ID))
                    .thenReturn(Optional.empty());

            SubscriptionPaymentPreferenceRequestDTO req =
                    SubscriptionPaymentPreferenceRequestDTO.builder()
                            .primaryPaymentMethodId(PRIMARY_PM_ID).build();

            assertThatThrownBy(() ->
                    preferenceService.setPaymentPreferences(MERCHANT_ID, SUBSCRIPTION_ID, req))
                    .isInstanceOf(MembershipException.class)
                    .hasMessageContaining("not found");
        }

        @Test
        @DisplayName("existing preference → updated in-place (not duplicated)")
        void setPreferences_update_replacesExisting() {
            when(subscriptionV2Repository.findCustomerIdByMerchantIdAndId(MERCHANT_ID, SUBSCRIPTION_ID))
                    .thenReturn(Optional.of(CUSTOMER_ID));
            when(paymentMethodRepository.findByMerchantIdAndCustomerIdAndId(MERCHANT_ID, CUSTOMER_ID, PRIMARY_PM_ID))
                    .thenReturn(Optional.of(activePrimaryPm));
            when(paymentMethodRepository.findByMerchantIdAndCustomerIdAndId(MERCHANT_ID, CUSTOMER_ID, BACKUP_PM_ID))
                    .thenReturn(Optional.of(activeBackupPm));

            SubscriptionPaymentPreference existing = SubscriptionPaymentPreference.builder()
                    .id(5L).subscriptionId(SUBSCRIPTION_ID)
                    .primaryPaymentMethodId(999L).build();
            when(preferenceRepository.findBySubscriptionId(SUBSCRIPTION_ID))
                    .thenReturn(Optional.of(existing));
            when(preferenceRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            SubscriptionPaymentPreferenceRequestDTO req =
                    SubscriptionPaymentPreferenceRequestDTO.builder()
                            .primaryPaymentMethodId(PRIMARY_PM_ID)
                            .backupPaymentMethodId(BACKUP_PM_ID).build();

            preferenceService.setPaymentPreferences(MERCHANT_ID, SUBSCRIPTION_ID, req);

            // The same entity (id=5) should be updated, not a new one created
            ArgumentCaptor<SubscriptionPaymentPreference> cap =
                    ArgumentCaptor.forClass(SubscriptionPaymentPreference.class);
            verify(preferenceRepository).save(cap.capture());
            assertThat(cap.getValue().getId()).isEqualTo(5L);
            assertThat(cap.getValue().getPrimaryPaymentMethodId()).isEqualTo(PRIMARY_PM_ID);
            assertThat(cap.getValue().getBackupPaymentMethodId()).isEqualTo(BACKUP_PM_ID);
        }
    }

    // ── getPreferencesForSubscription ─────────────────────────────────────────

    @Nested
    @DisplayName("getPreferencesForSubscription")
    class GetPreferences {

        @Test
        @DisplayName("found → returns DTO")
        void getPreferences_found() {
            when(subscriptionV2Repository.findCustomerIdByMerchantIdAndId(MERCHANT_ID, SUBSCRIPTION_ID))
                    .thenReturn(Optional.of(CUSTOMER_ID));
            SubscriptionPaymentPreference pref = SubscriptionPaymentPreference.builder()
                    .id(3L).subscriptionId(SUBSCRIPTION_ID)
                    .primaryPaymentMethodId(PRIMARY_PM_ID)
                    .backupPaymentMethodId(BACKUP_PM_ID).build();
            when(preferenceRepository.findBySubscriptionId(SUBSCRIPTION_ID))
                    .thenReturn(Optional.of(pref));

            SubscriptionPaymentPreferenceResponseDTO dto =
                    preferenceService.getPreferencesForSubscription(MERCHANT_ID, SUBSCRIPTION_ID);

            assertThat(dto.getPrimaryPaymentMethodId()).isEqualTo(PRIMARY_PM_ID);
            assertThat(dto.getBackupPaymentMethodId()).isEqualTo(BACKUP_PM_ID);
        }

        @Test
        @DisplayName("not set → 404")
        void getPreferences_notSet_404() {
            when(subscriptionV2Repository.findCustomerIdByMerchantIdAndId(MERCHANT_ID, SUBSCRIPTION_ID))
                    .thenReturn(Optional.of(CUSTOMER_ID));
            when(preferenceRepository.findBySubscriptionId(SUBSCRIPTION_ID))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() ->
                    preferenceService.getPreferencesForSubscription(MERCHANT_ID, SUBSCRIPTION_ID))
                    .isInstanceOf(MembershipException.class)
                    .hasMessageContaining("No payment preferences");
        }
    }
}
