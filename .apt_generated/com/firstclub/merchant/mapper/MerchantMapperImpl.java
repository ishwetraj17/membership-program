package com.firstclub.merchant.mapper;

import com.firstclub.merchant.dto.MerchantResponseDTO;
import com.firstclub.merchant.dto.MerchantSettingsDTO;
import com.firstclub.merchant.dto.MerchantUpdateRequestDTO;
import com.firstclub.merchant.entity.MerchantAccount;
import com.firstclub.merchant.entity.MerchantSettings;
import javax.annotation.processing.Generated;
import org.springframework.stereotype.Component;

@Generated(
    value = "org.mapstruct.ap.MappingProcessor",
    date = "2026-03-10T20:57:33+0530",
    comments = "version: 1.5.5.Final, compiler: Eclipse JDT (IDE) 3.45.0.v20260224-0835, environment: Java 21.0.10 (Eclipse Adoptium)"
)
@Component
public class MerchantMapperImpl implements MerchantMapper {

    @Override
    public MerchantResponseDTO toResponseDTO(MerchantAccount merchant) {
        if ( merchant == null ) {
            return null;
        }

        MerchantResponseDTO.MerchantResponseDTOBuilder merchantResponseDTO = MerchantResponseDTO.builder();

        merchantResponseDTO.settings( settingsToDTO( merchant.getSettings() ) );
        merchantResponseDTO.countryCode( merchant.getCountryCode() );
        merchantResponseDTO.createdAt( merchant.getCreatedAt() );
        merchantResponseDTO.defaultCurrency( merchant.getDefaultCurrency() );
        merchantResponseDTO.displayName( merchant.getDisplayName() );
        merchantResponseDTO.id( merchant.getId() );
        merchantResponseDTO.legalName( merchant.getLegalName() );
        merchantResponseDTO.merchantCode( merchant.getMerchantCode() );
        merchantResponseDTO.status( merchant.getStatus() );
        merchantResponseDTO.supportEmail( merchant.getSupportEmail() );
        merchantResponseDTO.timezone( merchant.getTimezone() );
        merchantResponseDTO.updatedAt( merchant.getUpdatedAt() );

        return merchantResponseDTO.build();
    }

    @Override
    public MerchantSettingsDTO settingsToDTO(MerchantSettings settings) {
        if ( settings == null ) {
            return null;
        }

        MerchantSettingsDTO.MerchantSettingsDTOBuilder merchantSettingsDTO = MerchantSettingsDTO.builder();

        merchantSettingsDTO.autoRetryEnabled( settings.getAutoRetryEnabled() );
        merchantSettingsDTO.createdAt( settings.getCreatedAt() );
        merchantSettingsDTO.defaultDunningPolicyCode( settings.getDefaultDunningPolicyCode() );
        merchantSettingsDTO.defaultGraceDays( settings.getDefaultGraceDays() );
        merchantSettingsDTO.id( settings.getId() );
        merchantSettingsDTO.metadataJson( settings.getMetadataJson() );
        merchantSettingsDTO.settlementFrequency( settings.getSettlementFrequency() );
        merchantSettingsDTO.updatedAt( settings.getUpdatedAt() );
        merchantSettingsDTO.webhookEnabled( settings.getWebhookEnabled() );

        return merchantSettingsDTO.build();
    }

    @Override
    public void updateEntityFromDTO(MerchantUpdateRequestDTO dto, MerchantAccount entity) {
        if ( dto == null ) {
            return;
        }

        if ( dto.getDefaultCurrency() != null ) {
            entity.setDefaultCurrency( dto.getDefaultCurrency() );
        }
        if ( dto.getDisplayName() != null ) {
            entity.setDisplayName( dto.getDisplayName() );
        }
        if ( dto.getLegalName() != null ) {
            entity.setLegalName( dto.getLegalName() );
        }
        if ( dto.getSupportEmail() != null ) {
            entity.setSupportEmail( dto.getSupportEmail() );
        }
        if ( dto.getTimezone() != null ) {
            entity.setTimezone( dto.getTimezone() );
        }
    }
}
