import os
import random
import datetime
import uuid

SECTORS = {
    "GOVERNMENT": [
        "after_action_report", "intel_brief", "policy_memo", "procurement_summary",
        "cyber_incident_report", "exercise_plan", "mission_overview", "compliance_audit",
        "risk_assessment", "system_architecture", "training_manual", "vendor_assessment",
        "operational_test", "threat_profile", "data_governance"
    ],
    "MEDICAL": [
        "clinical_trial_summary", "care_protocol", "hospital_ops_report", "quality_metrics",
        "lab_overview", "formulary_update", "equipment_maintenance", "staffing_plan",
        "safety_incident", "compliance_audit", "patient_outcomes_aggregate", "utilization_review",
        "research_overview", "risk_assessment", "vendor_assessment"
    ],
    "ENTERPRISE": [
        "strategy_memo", "transformation_plan", "operational_review", "security_incident",
        "compliance_audit", "vendor_assessment", "product_brief", "customer_insights",
        "quarterly_business_review", "budget_forecast", "risk_assessment", "IT_architecture",
        "change_management", "legal_contract_review", "policy_update"
    ]
}

TOPICS = ["infrastructure", "budget", "personnel", "logistics", "cybersecurity", "compliance", "innovation", "legacy_systems", "strategic_alignment"]
ROLES = ["Analyst", "Manager", "Director", "Officer", "Coordinator", "Specialist", "Administrator"]
WORDS = ["operational", "efficiency", "strategic", "deployment", "bandwidth", "synergy", "paradigm", "holistic", "scalability", "leverage", "robust", "sustainable", "granular", "proactive", "stakeholder", "ecosystem", "methodology", "framework", "compliance", "mitigation", "vector", "latency", "throughput", "optimization", "integration", "governance", "resilience", "redundancy", "authentication", "authorization", "protocol", "audit", "forecast", "variance", "quarterly", "fiscal", "clinical", "patient", "protocol", "efficacy", "cohort", "longitudinal", "quantitative", "qualitative", "empirical", "hypothesis", "pedagogy", "curriculum", "tenure", "grant", "sabbatical", "endowment", "liquidity", "solvency", "amortization", "depreciation", "ledger", "audit", "taxation", "revenue", "expenditure", "liability", "asset", "equity", "dividend"]

BASE_DIR = r"D:\Corpus"

def generate_content(min_words=300, max_words=1200):
    target_count = random.randint(min_words, max_words)
    content = []
    
    while len(content) < target_count:
        sentence_len = random.randint(5, 20)
        sentence = [random.choice(WORDS) for _ in range(sentence_len)]
        sentence[0] = sentence[0].capitalize()
        content.extend(sentence)
        content[-1] += "."
        
    return " ".join(content)

def main():
    if not os.path.exists(BASE_DIR):
        os.makedirs(BASE_DIR)
        
    total_files = 0
    start_time = datetime.datetime.now()
    
    for sector, categories in SECTORS.items():
        sector_dir = os.path.join(BASE_DIR, sector.title())
        if not os.path.exists(sector_dir):
            os.makedirs(sector_dir)
            
        print(f"Generating 1000 docs for {sector}...")
        
        for i in range(1, 1001):
            category = random.choice(categories)
            topic = random.choice(TOPICS)
            doc_uuid = str(uuid.uuid4())[:8]
            
            filename = f"{sector.lower()}_{category}_{topic}_{i:04d}.txt"
            filepath = os.path.join(sector_dir, filename)
            
            # Metadata
            title = f"{sector} {category.replace('_', ' ').title()} - {topic.title()}"
            date_str = (datetime.date.today() - datetime.timedelta(days=random.randint(0, 1000))).isoformat()
            role = f"{random.choice(ROLES)}"
            
            content_body = generate_content()
            
            file_content = f"""DOC_ID: {doc_uuid}
SECTOR: {sector}
TITLE: {title}
DATE: {date_str}
AUTHOR_ROLE: {role}
CLASSIFICATION: UNCLASSIFIED // FOR TRAINING
SUMMARY: This document outlines the key findings regarding {topic} within the {category} context. It aims to provide strategic oversight and granular analysis.

=== FILE: {filename} ===

{topic.upper()} OVERVIEW
--------------------------------------------------
{content_body[:len(content_body)//3]}

DETAILED ANALYSIS of {category.upper()}
--------------------------------------------------
{content_body[len(content_body)//3:2*len(content_body)//3]}

FINDINGS AND RECOMMENDATIONS
--------------------------------------------------
{content_body[2*len(content_body)//3:]}

CONFIDENTIALITY NOTICE: This document contains proprietary information.
[REDACTED-NAME] approved this release on [REDACTED-DATE].
"""
            with open(filepath, "w", encoding="utf-8") as f:
                f.write(file_content)
                
            total_files += 1
            
    end_time = datetime.datetime.now()
    duration = end_time - start_time
    print(f"Done. Generated {total_files} files in {duration}.")

if __name__ == "__main__":
    main()
