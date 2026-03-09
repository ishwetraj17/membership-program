package com.firstclub.billing.tax.service;

import com.firstclub.billing.tax.dto.*;
import com.firstclub.billing.tax.entity.*;
import com.firstclub.billing.tax.repository.CustomerTaxProfileRepository;
import com.firstclub.billing.tax.repository.TaxProfileRepository;
import com.firstclub.billing.tax.service.impl.TaxProfileServiceImpl;
import com.firstclub.membership.exception.MembershipException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TaxProfileServiceTest {

    @Mock private TaxProfileRepository taxProfileRepository;
    @Mock private CustomerTaxProfileRepository customerTaxProfileRepository;

    @InjectMocks
    private TaxProfileServiceImpl service;

    private static final Long MERCHANT_ID  = 1L;
    private static final Long CUSTOMER_ID  = 2L;

    // ── Merchant tax profile ──────────────────────────────────────────────────

    @Test
    @DisplayName("createOrUpdate: new merchant profile saved and returned")
    void createMerchantProfile_noExisting_savesNew() {
        TaxProfileCreateOrUpdateRequestDTO req = TaxProfileCreateOrUpdateRequestDTO.builder()
                .gstin("27AAAAA0000A1Z5").legalStateCode("MH")
                .registeredBusinessName("Acme Ltd").taxMode(TaxMode.B2B).build();

        when(taxProfileRepository.findByMerchantId(MERCHANT_ID)).thenReturn(Optional.empty());
        when(taxProfileRepository.save(any())).thenAnswer(inv -> {
            TaxProfile p = inv.getArgument(0);
            p = TaxProfile.builder()
                    .id(10L).merchantId(p.getMerchantId()).gstin(p.getGstin())
                    .legalStateCode(p.getLegalStateCode())
                    .registeredBusinessName(p.getRegisteredBusinessName())
                    .taxMode(p.getTaxMode()).createdAt(LocalDateTime.now()).build();
            return p;
        });

        TaxProfileResponseDTO resp = service.createOrUpdateMerchantTaxProfile(MERCHANT_ID, req);

        assertThat(resp.getId()).isEqualTo(10L);
        assertThat(resp.getMerchantId()).isEqualTo(MERCHANT_ID);
        assertThat(resp.getGstin()).isEqualTo("27AAAAA0000A1Z5");
        assertThat(resp.getLegalStateCode()).isEqualTo("MH");
        assertThat(resp.getTaxMode()).isEqualTo(TaxMode.B2B);

        ArgumentCaptor<TaxProfile> captor = ArgumentCaptor.forClass(TaxProfile.class);
        verify(taxProfileRepository).save(captor.capture());
        assertThat(captor.getValue().getMerchantId()).isEqualTo(MERCHANT_ID);
    }

    @Test
    @DisplayName("createOrUpdate: existing merchant profile updated in place")
    void updateMerchantProfile_existing_updatesFields() {
        TaxProfile existing = TaxProfile.builder()
                .id(5L).merchantId(MERCHANT_ID).gstin("OLD_GSTIN")
                .legalStateCode("DL").registeredBusinessName("Old Name")
                .taxMode(TaxMode.B2C).createdAt(LocalDateTime.now()).build();

        TaxProfileCreateOrUpdateRequestDTO req = TaxProfileCreateOrUpdateRequestDTO.builder()
                .gstin("27AAAAA0000A1Z5").legalStateCode("MH")
                .registeredBusinessName("New Name").taxMode(TaxMode.B2B).build();

        when(taxProfileRepository.findByMerchantId(MERCHANT_ID)).thenReturn(Optional.of(existing));
        when(taxProfileRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        TaxProfileResponseDTO resp = service.createOrUpdateMerchantTaxProfile(MERCHANT_ID, req);

        assertThat(resp.getId()).isEqualTo(5L);  // same entity, not a new one
        assertThat(resp.getGstin()).isEqualTo("27AAAAA0000A1Z5");
        assertThat(resp.getTaxMode()).isEqualTo(TaxMode.B2B);
    }

    @Test
    @DisplayName("getMerchantTaxProfile: profile found → returned")
    void getMerchantProfile_found_returnsDto() {
        TaxProfile p = TaxProfile.builder()
                .id(5L).merchantId(MERCHANT_ID).gstin("27AAAAA0000A1Z5")
                .legalStateCode("MH").registeredBusinessName("Club Ltd")
                .taxMode(TaxMode.B2B).createdAt(LocalDateTime.now()).build();

        when(taxProfileRepository.findByMerchantId(MERCHANT_ID)).thenReturn(Optional.of(p));

        TaxProfileResponseDTO resp = service.getMerchantTaxProfile(MERCHANT_ID);
        assertThat(resp.getMerchantId()).isEqualTo(MERCHANT_ID);
        assertThat(resp.getGstin()).isEqualTo("27AAAAA0000A1Z5");
    }

    @Test
    @DisplayName("getMerchantTaxProfile: not found → MembershipException 404")
    void getMerchantProfile_notFound_throws404() {
        when(taxProfileRepository.findByMerchantId(MERCHANT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getMerchantTaxProfile(MERCHANT_ID))
                .isInstanceOf(MembershipException.class)
                .hasMessageContaining("merchant");
    }

    // ── Customer tax profile ──────────────────────────────────────────────────

    @Test
    @DisplayName("createOrUpdate: new customer profile saved and returned")
    void createCustomerProfile_noExisting_savesNew() {
        CustomerTaxProfileCreateOrUpdateRequestDTO req =
                CustomerTaxProfileCreateOrUpdateRequestDTO.builder()
                        .gstin("27BBBBB1111B1Z5").stateCode("MH")
                        .entityType(CustomerEntityType.BUSINESS).taxExempt(false).build();

        when(customerTaxProfileRepository.findByCustomerId(CUSTOMER_ID)).thenReturn(Optional.empty());
        when(customerTaxProfileRepository.save(any())).thenAnswer(inv -> {
            CustomerTaxProfile p = inv.getArgument(0);
            return CustomerTaxProfile.builder()
                    .id(20L).customerId(p.getCustomerId()).gstin(p.getGstin())
                    .stateCode(p.getStateCode()).entityType(p.getEntityType())
                    .taxExempt(p.isTaxExempt()).createdAt(LocalDateTime.now()).build();
        });

        CustomerTaxProfileResponseDTO resp =
                service.createOrUpdateCustomerTaxProfile(CUSTOMER_ID, req);

        assertThat(resp.getId()).isEqualTo(20L);
        assertThat(resp.getCustomerId()).isEqualTo(CUSTOMER_ID);
        assertThat(resp.getStateCode()).isEqualTo("MH");
        assertThat(resp.isTaxExempt()).isFalse();
    }

    @Test
    @DisplayName("createOrUpdate: tax-exempt customer profile saved correctly")
    void createCustomerProfile_taxExempt_savedWithFlag() {
        CustomerTaxProfileCreateOrUpdateRequestDTO req =
                CustomerTaxProfileCreateOrUpdateRequestDTO.builder()
                        .stateCode("KA").entityType(CustomerEntityType.INDIVIDUAL)
                        .taxExempt(true).build();

        when(customerTaxProfileRepository.findByCustomerId(CUSTOMER_ID)).thenReturn(Optional.empty());
        when(customerTaxProfileRepository.save(any())).thenAnswer(inv -> {
            CustomerTaxProfile p = inv.getArgument(0);
            return CustomerTaxProfile.builder()
                    .id(21L).customerId(p.getCustomerId())
                    .stateCode(p.getStateCode()).entityType(p.getEntityType())
                    .taxExempt(p.isTaxExempt()).createdAt(LocalDateTime.now()).build();
        });

        CustomerTaxProfileResponseDTO resp =
                service.createOrUpdateCustomerTaxProfile(CUSTOMER_ID, req);

        assertThat(resp.isTaxExempt()).isTrue();
        assertThat(resp.getEntityType()).isEqualTo(CustomerEntityType.INDIVIDUAL);
    }

    @Test
    @DisplayName("getCustomerTaxProfile: not found → MembershipException 404")
    void getCustomerProfile_notFound_throws404() {
        when(customerTaxProfileRepository.findByCustomerId(CUSTOMER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getCustomerTaxProfile(CUSTOMER_ID))
                .isInstanceOf(MembershipException.class)
                .hasMessageContaining("customer");
    }
}
