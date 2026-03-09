package com.firstclub.subscription.mapper;

import com.firstclub.subscription.dto.SubscriptionScheduleCreateRequestDTO;
import com.firstclub.subscription.dto.SubscriptionScheduleResponseDTO;
import com.firstclub.subscription.entity.SubscriptionSchedule;
import com.firstclub.subscription.entity.SubscriptionV2;
import javax.annotation.processing.Generated;
import org.springframework.stereotype.Component;

@Generated(
    value = "org.mapstruct.ap.MappingProcessor",
    date = "2026-03-09T09:53:54+0530",
    comments = "version: 1.5.5.Final, compiler: Eclipse JDT (IDE) 3.45.0.v20260224-0835, environment: Java 21.0.10 (Eclipse Adoptium)"
)
@Component
public class SubscriptionScheduleMapperImpl implements SubscriptionScheduleMapper {

    @Override
    public SubscriptionScheduleResponseDTO toResponseDTO(SubscriptionSchedule entity) {
        if ( entity == null ) {
            return null;
        }

        SubscriptionScheduleResponseDTO.SubscriptionScheduleResponseDTOBuilder subscriptionScheduleResponseDTO = SubscriptionScheduleResponseDTO.builder();

        subscriptionScheduleResponseDTO.subscriptionId( entitySubscriptionId( entity ) );
        subscriptionScheduleResponseDTO.createdAt( entity.getCreatedAt() );
        subscriptionScheduleResponseDTO.effectiveAt( entity.getEffectiveAt() );
        subscriptionScheduleResponseDTO.id( entity.getId() );
        subscriptionScheduleResponseDTO.payloadJson( entity.getPayloadJson() );
        subscriptionScheduleResponseDTO.scheduledAction( entity.getScheduledAction() );
        subscriptionScheduleResponseDTO.status( entity.getStatus() );
        subscriptionScheduleResponseDTO.updatedAt( entity.getUpdatedAt() );

        return subscriptionScheduleResponseDTO.build();
    }

    @Override
    public SubscriptionSchedule toEntity(SubscriptionScheduleCreateRequestDTO dto) {
        if ( dto == null ) {
            return null;
        }

        SubscriptionSchedule.SubscriptionScheduleBuilder subscriptionSchedule = SubscriptionSchedule.builder();

        subscriptionSchedule.effectiveAt( dto.getEffectiveAt() );
        subscriptionSchedule.payloadJson( dto.getPayloadJson() );
        subscriptionSchedule.scheduledAction( dto.getScheduledAction() );

        return subscriptionSchedule.build();
    }

    private Long entitySubscriptionId(SubscriptionSchedule subscriptionSchedule) {
        if ( subscriptionSchedule == null ) {
            return null;
        }
        SubscriptionV2 subscription = subscriptionSchedule.getSubscription();
        if ( subscription == null ) {
            return null;
        }
        Long id = subscription.getId();
        if ( id == null ) {
            return null;
        }
        return id;
    }
}
