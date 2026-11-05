"""Makes the encrypted copies of sample.pdf for the PDF reader's tests: one with
AES-256 and one with RC4 (128-bit), each locked with an owner password only, so
they open with the empty user password as viewers open them, and one that needs
a password to open.

Run: python3 make_pdf_fixtures.py <pdf_samples dir>  (needs PyMuPDF)"""
import sys

import fitz

out = sys.argv[1] if len(sys.argv) > 1 else "."
doc = fitz.open(f"{out}/sample.pdf")
doc.save(f"{out}/sample_aes256.pdf", encryption=fitz.PDF_ENCRYPT_AES_256, owner_pw="owner", user_pw="",
         permissions=fitz.PDF_PERM_PRINT)
doc.save(f"{out}/sample_rc4.pdf", encryption=fitz.PDF_ENCRYPT_RC4_128, owner_pw="owner", user_pw="",
         permissions=fitz.PDF_PERM_PRINT)
doc.save(f"{out}/sample_locked.pdf", encryption=fitz.PDF_ENCRYPT_AES_128, owner_pw="owner", user_pw="user")
print("encrypted samples written")
