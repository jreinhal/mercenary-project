#!/usr/bin/env python3
"""
SENTINEL License Key Generator

Generates HMAC-SHA256 signed license keys for SENTINEL editions.

Usage:
    python generate-license-key.py --edition ENTERPRISE --expiry 2027-12-31 \
        --customer "Acme Corp" --secret "your-signing-secret"

The generated key should be set as the LICENSE_KEY environment variable
alongside LICENSE_SIGNING_SECRET for the SENTINEL application.
"""

import argparse
import base64
import hashlib
import hmac
import sys
from datetime import datetime


VALID_EDITIONS = ["TRIAL", "ENTERPRISE", "MEDICAL", "GOVERNMENT"]


def compute_hmac(data: str, secret: str) -> str:
    """Compute HMAC-SHA256 hex digest (matches Java LicenseService.computeHmac)."""
    return hmac.new(
        secret.encode("utf-8"),
        data.encode("utf-8"),
        hashlib.sha256
    ).hexdigest()


def generate_license_key(edition: str, expiry: str, customer_id: str, secret: str) -> str:
    """Generate a SENTINEL license key.

    Format: BASE64(edition:expiry:customerId):HMAC_SHA256_HEX
    """
    payload = f"{edition}:{expiry}:{customer_id}"
    payload_base64 = base64.b64encode(payload.encode("utf-8")).decode("utf-8")
    signature = compute_hmac(payload_base64, secret)
    return f"{payload_base64}:{signature}"


def validate_date(date_str: str) -> str:
    """Validate date format (YYYY-MM-DD)."""
    try:
        parsed = datetime.strptime(date_str, "%Y-%m-%d")
        if parsed.date() < datetime.now().date():
            print(f"WARNING: Expiry date {date_str} is in the past!", file=sys.stderr)
        return date_str
    except ValueError:
        raise argparse.ArgumentTypeError(f"Invalid date format: {date_str}. Use YYYY-MM-DD")


def main():
    parser = argparse.ArgumentParser(
        description="Generate SENTINEL license keys with HMAC-SHA256 signatures"
    )
    parser.add_argument(
        "--edition", required=True, choices=VALID_EDITIONS,
        help="License edition"
    )
    parser.add_argument(
        "--expiry", required=True, type=validate_date,
        help="Expiry date (YYYY-MM-DD)"
    )
    parser.add_argument(
        "--customer", required=True,
        help="Customer ID or name"
    )
    parser.add_argument(
        "--secret", required=True,
        help="HMAC signing secret (must match LICENSE_SIGNING_SECRET in app config)"
    )
    parser.add_argument(
        "--verify", action="store_true",
        help="Verify the generated key by decoding and re-signing"
    )

    args = parser.parse_args()

    key = generate_license_key(args.edition, args.expiry, args.customer, args.secret)

    print(f"\n{'='*60}")
    print(f"SENTINEL License Key Generated")
    print(f"{'='*60}")
    print(f"Edition:    {args.edition}")
    print(f"Expiry:     {args.expiry}")
    print(f"Customer:   {args.customer}")
    print(f"{'='*60}")
    print(f"\nLICENSE_KEY={key}")
    print(f"\n{'='*60}")

    if args.verify:
        # Verify by splitting and re-computing
        sep = key.rfind(":")
        payload_b64 = key[:sep]
        sig = key[sep+1:]
        expected_sig = compute_hmac(payload_b64, args.secret)

        payload = base64.b64decode(payload_b64).decode("utf-8")
        parts = payload.split(":", 2)

        print(f"\nVerification:")
        print(f"  Payload (decoded): {payload}")
        print(f"  Signature match:   {hmac.compare_digest(sig, expected_sig)}")
        print(f"  Edition:           {parts[0]}")
        print(f"  Expiry:            {parts[1]}")
        print(f"  Customer:          {parts[2]}")

    print(f"\nSet these environment variables:")
    print(f"  LICENSE_KEY={key}")
    print(f"  LICENSE_SIGNING_SECRET={args.secret}")
    print(f"  sentinel.license.edition={args.edition}")


if __name__ == "__main__":
    main()
