/**
 * SENTINEL Sector Configuration
 *
 * Simplified single-product model with 5 sectors.
 * UI uses USWDS-compliant light/dark mode only (no sector-specific themes).
 * Color scheme follows Section 508 and WCAG 2.1 AA accessibility standards.
 *
 * Sectors:
 * - GOVERNMENT: Defense/Intel (SECRET clearance)
 * - MEDICAL: Healthcare/HIPAA (SECRET clearance)
 * - FINANCE: Banking/PCI-DSS (CUI clearance)
 * - ACADEMIC: Research/Literature (UNCLASSIFIED)
 * - ENTERPRISE: General Business (UNCLASSIFIED)
 */

window.SENTINEL_SECTORS = ['GOVERNMENT', 'MEDICAL', 'FINANCE', 'ACADEMIC', 'ENTERPRISE'];

window.SECTOR_CONFIG = {
    // ========================================
    // HIGH-SECURITY SECTORS (Elevated Clearance)
    // ========================================

    'GOVERNMENT': {
        label: 'Government / Defense',
        shortLabel: 'GOV',
        description: 'Classified intelligence and defense documents',
        compliance: 'CLASSIFIED',
        clearance: 'SECRET',
        icon: 'shield',
        placeholders: {
            query: 'Enter intelligence query...',
            upload: 'Upload classified documents...'
        },
        features: {
            showClearanceBadge: true,
            showClassificationBanner: true,
            requireClearance: true,
            classificationLevel: 'secret'
        }
    },

    'MEDICAL': {
        label: 'Medical / Healthcare',
        shortLabel: 'MED',
        description: 'Clinical records and HIPAA-protected data',
        compliance: 'HIPAA',
        clearance: 'SECRET',
        icon: 'heart',
        placeholders: {
            query: 'Search clinical records...',
            upload: 'Upload medical documents...'
        },
        features: {
            showClearanceBadge: true,
            showClassificationBanner: false,
            requireClearance: true,
            classificationLevel: 'cui'
        }
    },

    'FINANCE': {
        label: 'Finance / Banking',
        shortLabel: 'FIN',
        description: 'Financial records and PCI-DSS protected data',
        compliance: 'PCI-DSS',
        clearance: 'CUI',
        icon: 'dollar',
        placeholders: {
            query: 'Search financial records...',
            upload: 'Upload financial documents...'
        },
        features: {
            showClearanceBadge: true,
            showClassificationBanner: false,
            requireClearance: true,
            classificationLevel: 'cui'
        }
    },

    // ========================================
    // STANDARD SECTORS (No Elevated Clearance)
    // ========================================

    'ACADEMIC': {
        label: 'Academic / Research',
        shortLabel: 'EDU',
        description: 'Research papers and academic literature',
        compliance: 'Research Data',
        clearance: 'UNCLASSIFIED',
        icon: 'book',
        placeholders: {
            query: 'Search literature...',
            upload: 'Upload research papers...'
        },
        features: {
            showClearanceBadge: false,
            showClassificationBanner: false,
            requireClearance: false,
            classificationLevel: 'unclassified',
            emphasizeCitations: true
        }
    },

    'ENTERPRISE': {
        label: 'Enterprise / Corporate',
        shortLabel: 'ENT',
        description: 'General business, legal, and corporate documents',
        compliance: 'General Business',
        clearance: 'UNCLASSIFIED',
        icon: 'building',
        placeholders: {
            query: 'Search documents...',
            upload: 'Upload documents...'
        },
        features: {
            showClearanceBadge: false,
            showClassificationBanner: false,
            requireClearance: false,
            classificationLevel: 'unclassified'
        }
    }
};

// Default sector
window.SENTINEL_DEFAULT_SECTOR = 'ENTERPRISE';

// Default display mode (respects system preference)
window.SENTINEL_DEFAULT_DISPLAY_MODE = 'auto';

// Splash screen content (core platform features)
window.SENTINEL_SPLASH = {
    subtitle: 'AI-powered document intelligence with transparent reasoning, strict citation anchoring, and full auditability.',
    features: [
        {
            icon: 'citation',
            title: 'Citation Anchoring',
            desc: 'Click any citation to jump to the exact source passage.'
        },
        {
            icon: 'glassbox',
            title: 'Glass Box Reasoning',
            desc: 'View the full retrieval chain for complete transparency.'
        },
        {
            icon: 'sectors',
            title: 'Sector Isolation',
            desc: 'Documents partitioned by domain with access controls.'
        },
        {
            icon: 'context',
            title: 'Context Control',
            desc: 'Select which documents inform each query.'
        }
    ]
};

/**
 * Get configuration for a sector
 */
window.getSectorConfig = function(sector) {
    return window.SECTOR_CONFIG[sector] || window.SECTOR_CONFIG['ENTERPRISE'];
};

/**
 * Check if sector requires elevated clearance
 */
window.sectorRequiresClearance = function(sector) {
    const config = window.getSectorConfig(sector);
    return config.features?.requireClearance || false;
};

/**
 * Get classification level for sector
 */
window.getSectorClassification = function(sector) {
    const config = window.getSectorConfig(sector);
    return config.features?.classificationLevel || 'unclassified';
};

/**
 * Initialize display mode based on preference
 * Returns 'light' or 'dark'
 */
window.getDisplayMode = function(preference) {
    if (preference === 'light') return 'light';
    if (preference === 'dark') return 'dark';
    // Auto mode: check system preference
    if (window.matchMedia && window.matchMedia('(prefers-color-scheme: dark)').matches) {
        return 'dark';
    }
    return 'light';
};
