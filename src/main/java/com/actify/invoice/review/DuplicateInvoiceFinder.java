package com.actify.invoice.review;

import com.actify.invoice.entity.Invoice;
import com.actify.invoice.repository.InvoiceRepository;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * Business duplicate check ("the same invoice uploaded twice"), ALWAYS scoped to one vendor.
 *
 * Two invoices of the SAME vendor are duplicates when
 *   - their invoice numbers are equal after normalization (upper case, only A-Z and 0-9: "inv-001 " = "INV001"), and
 *   - their sellers are the same: by seller tax id when BOTH have one, otherwise by normalized seller name
 *     (lower case, punctuation and repeated spaces removed).
 * Missing invoice number or missing seller identity -> no duplicate is claimed (better a reviewer checks than a false
 * block). FAILED and REJECTED invoices do not count. Another vendor's invoices are NEVER looked at, so one vendor can
 * neither be blocked by nor learn anything about another vendor's uploads.
 *
 * The exact-file check (same SHA-256 bytes, same vendor) happens earlier, at intake (BatchIntakeService).
 * Limitation: the normalized key is computed here, not stored in its own column (that would need a schema migration);
 * with the per-vendor volumes of this app the candidate list is small.
 */
@Component
public class DuplicateInvoiceFinder {

    private final InvoiceRepository invoiceRepository;

    public DuplicateInvoiceFinder(InvoiceRepository invoiceRepository) {
        this.invoiceRepository = invoiceRepository;
    }

    /** The earliest other invoice of the same vendor that this one duplicates, or null. Legacy rows (no owner): null. */
    public Invoice findSameVendorDuplicate(Invoice invoice, String invoiceNumber, String sellerTaxId, String sellerName) {
        String number = normalizeInvoiceNumber(invoiceNumber);
        if (invoice.getOwnerUserId() == null || number == null || sellerKey(sellerTaxId, sellerName) == null) {
            return null;
        }
        for (Invoice other : invoiceRepository.findDuplicateCandidates(invoice.getOwnerUserId(), invoice.getId())) {
            if (number.equals(normalizeInvoiceNumber(other.getInvoiceNumber()))
                    && sameSeller(sellerTaxId, sellerName, other.getSellerTaxId(), other.getSellerName())) {
                return other;
            }
        }
        return null;
    }

    /** Text for the vendor and the reviewer. Only ever describes the SAME vendor's own earlier invoice. */
    public static String describe(Invoice earlier) {
        return "You already uploaded invoice " + earlier.getInvoiceNumber()
                + (earlier.getSellerName() == null ? "" : " from " + earlier.getSellerName())
                + " (invoice " + earlier.getId()
                + (earlier.getCreatedAt() == null ? "" : ", uploaded " + earlier.getCreatedAt().toLocalDate()) + ").";
    }

    static String normalizeInvoiceNumber(String raw) {
        if (raw == null) {
            return null;
        }
        String n = raw.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
        return n.isEmpty() ? null : n;
    }

    static String normalizeTaxId(String raw) {
        return normalizeInvoiceNumber(raw); // same rule: case-insensitive, separators ignored
    }

    static String normalizeSellerName(String raw) {
        if (raw == null) {
            return null;
        }
        String n = raw.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{Nd}]+", " ").trim();
        return n.isEmpty() ? null : n;
    }

    private static String sellerKey(String taxId, String name) {
        String tax = normalizeTaxId(taxId);
        return tax != null ? tax : normalizeSellerName(name);
    }

    static boolean sameSeller(String taxA, String nameA, String taxB, String nameB) {
        String ta = normalizeTaxId(taxA);
        String tb = normalizeTaxId(taxB);
        if (ta != null && tb != null) {
            return ta.equals(tb);
        }
        String na = normalizeSellerName(nameA);
        String nb = normalizeSellerName(nameB);
        return na != null && na.equals(nb);
    }
}
