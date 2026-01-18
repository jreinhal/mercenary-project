# SENTINEL Ansible Deployment

Automated deployment for SENTINEL Intelligence Platform using Ansible.

## Directory Structure

```
ansible/
├── inventory/
│   ├── production.yml      # Production hosts
│   ├── staging.yml         # Staging hosts
│   └── airgap.yml          # Air-gapped/SCIF hosts
├── group_vars/
│   ├── all.yml             # Common variables
│   ├── production.yml      # Production-specific
│   └── airgap.yml          # Air-gap specific
├── roles/
│   ├── sentinel/           # Main application
│   ├── ollama/             # Local LLM inference
│   └── mongodb/            # Vector store
├── playbooks/
│   ├── site.yml            # Full deployment
│   ├── sentinel.yml        # App only
│   └── upgrade.yml         # Rolling upgrade
└── ansible.cfg             # Ansible configuration
```

## Quick Start

### Prerequisites

```bash
# Install Ansible
pip install ansible

# For air-gapped environments, pre-download collections
ansible-galaxy collection download community.docker
ansible-galaxy collection download community.mongodb
```

### Deploy to Staging

```bash
ansible-playbook -i inventory/staging.yml playbooks/site.yml
```

### Deploy to Production

```bash
ansible-playbook -i inventory/production.yml playbooks/site.yml --check  # Dry run
ansible-playbook -i inventory/production.yml playbooks/site.yml          # Deploy
```

### Air-Gapped Deployment

```bash
# On connected machine: package everything
./scripts/package-airgap.sh

# Transfer package to air-gapped network
# On air-gapped machine:
ansible-playbook -i inventory/airgap.yml playbooks/site.yml --extra-vars "offline=true"
```

## Editions

Set the `sentinel_edition` variable:

| Edition | Value | Features |
|---------|-------|----------|
| Trial | `trial` | 30-day, all features |
| Professional | `professional` | Commercial |
| Medical | `medical` | HIPAA compliance |
| Government | `government` | SCIF/CAC/clearance |

```bash
ansible-playbook playbooks/site.yml -e "sentinel_edition=government"
```

## Security

### Vault for Secrets

```bash
# Create encrypted vault
ansible-vault create group_vars/vault.yml

# Edit vault
ansible-vault edit group_vars/vault.yml

# Run with vault
ansible-playbook playbooks/site.yml --ask-vault-pass
```

### Variables to Encrypt

- `vault_mongodb_password`
- `vault_admin_password`
- `vault_jwt_secret`
- `vault_encryption_key`

## Roles

### sentinel

Deploys the SENTINEL application:
- Java 21 runtime
- Application JAR
- Systemd service
- Nginx reverse proxy (optional)
- TLS certificates

### ollama

Deploys Ollama for local LLM:
- Ollama service
- Model downloads (llama3, mistral, etc.)
- GPU driver setup (if available)

### mongodb

Deploys MongoDB for vector storage:
- MongoDB 7.x
- Authentication setup
- Replica set (optional)
- Backup configuration

## Tags

Run specific tasks:

```bash
# Only update application
ansible-playbook playbooks/site.yml --tags "sentinel"

# Only configure TLS
ansible-playbook playbooks/site.yml --tags "tls"

# Skip model downloads (for bandwidth)
ansible-playbook playbooks/site.yml --skip-tags "models"
```

## Idempotency

All playbooks are idempotent - safe to run multiple times. State checks ensure:
- Services only restart when configuration changes
- Models only download if not present
- Certificates only regenerate when expiring

## Compliance

For government deployments, enable STIG hardening:

```bash
ansible-playbook playbooks/site.yml -e "stig_hardening=true"
```

This enables:
- Fail-closed audit logging
- Restricted file permissions
- SELinux enforcement
- FIPS 140-2 cryptography

## Troubleshooting

### Check connectivity
```bash
ansible -i inventory/staging.yml all -m ping
```

### Verbose output
```bash
ansible-playbook playbooks/site.yml -vvv
```

### Dry run
```bash
ansible-playbook playbooks/site.yml --check --diff
```
