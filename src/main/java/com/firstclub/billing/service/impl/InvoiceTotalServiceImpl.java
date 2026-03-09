package com.firstclub.billing.service.impl;

import com.firstclub.billing.entity.Invoice;
import com.firstclub.billing.entity.InvoiceLine;
import com.firstclub.billing.repository.InvoiceLineRepository;
import com.firstclub.billing.repository.InvoiceRepository;
import com.firstclub.billing.service.InvoiceTotalService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

@Service
@RequiredArgsConstructor
public class InvoiceTotalServiceImpl implements InvoiceTotalService {

    private final InvoiceLineRepository invoiceLineRepository;
    private final InvoiceRepository invoiceRepository;

    @Override
    @Transactional
    public Invoice recomputeTotals(Invoice invoice) {
        List<InvoiceLine> lines = invoiceLineRepository.findByInvoiceId(invoice.getId());

        BigDecimal subtotal      = BigDecimal.ZERO;
        BigDecimal taxTotal      = BigDecimal.ZERO;
        BigDecimal discountTotal = BigDecimal.ZERO;
        BigDecimal creditTotal   = BigDecimal.ZERO;

        for (InvoiceLine line : lines) {
            BigDecimal amount = line.getAmount();
            switch (line.getLineType()) {
                case PLAN_CHARGE, PRORATION   -> subtotal = subtotal.add(amount);
                case TAX                       -> taxTotal = taxTotal.add(amount);
                case CGST, SGST, IGST          -> taxTotal = taxTotal.add(amount);
                case DISCOUNT                  -> discountTotal = discountTotal.add(amount.abs());
                case CREDIT_APPLIED            -> creditTotal = creditTotal.add(amount.abs());
            }
        }

        BigDecimal grandTotal = subtotal
                .subtract(discountTotal)
                .subtract(creditTotal)
                .add(taxTotal)
                .max(BigDecimal.ZERO);

        invoice.setSubtotal(subtotal);
        invoice.setTaxTotal(taxTotal);
        invoice.setDiscountTotal(discountTotal);
        invoice.setCreditTotal(creditTotal);
        invoice.setGrandTotal(grandTotal);
        // keep totalAmount in sync for backward compat
        invoice.setTotalAmount(grandTotal);

        return invoiceRepository.save(invoice);
    }
}
