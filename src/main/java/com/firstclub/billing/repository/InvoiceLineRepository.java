package com.firstclub.billing.repository;

import com.firstclub.billing.entity.InvoiceLine;
import com.firstclub.billing.entity.InvoiceLineType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface InvoiceLineRepository extends JpaRepository<InvoiceLine, Long> {

    List<InvoiceLine> findByInvoiceId(Long invoiceId);

    void deleteByInvoiceId(Long invoiceId);

    void deleteByInvoiceIdAndLineTypeIn(Long invoiceId, Collection<InvoiceLineType> lineTypes);
}
