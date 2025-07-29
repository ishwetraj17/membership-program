#!/usr/bin/env python3
"""
FirstClub Membership Program - USER STRESS TESTING SUITE
========================================================
Ultra-intensive user stress testing with 5000 users performing complex 
subscription operations across all 9 membership plans with comprehensive
business logic validation.

Features:
- 5000+ users with complete lifecycle testing
- All 9 membership plans with complex tier/duration combinations
- Intensive upgrade/downgrade operations with price calculations
- Business logic validation for tier hierarchies and pricing
- Complex subscription pattern testing
- Real-time performance monitoring
- Comprehensive error handling and validation

Author: Shwet Raj
Date: July 12, 2025
Purpose: Intensive user subscription stress testing
"""

import requests
import json
import time
import random
import statistics
import concurrent.futures
from datetime import datetime, timedelta
from typing import Dict, List, Any, Optional
from dataclasses import dataclass
import sys

@dataclass
class UserProfile:
    id: Optional[int] = None
    name: str = ""
    email: str = ""
    phone: str = ""
    address: str = ""
    city: str = ""
    state: str = ""
    pincode: str = ""
    current_subscription_id: Optional[int] = None
    current_plan_id: Optional[int] = None
    current_tier: str = ""
    subscription_history: List[Dict] = None
    total_upgrades: int = 0
    total_downgrades: int = 0
    total_price_paid: float = 0.0
    total_pro_rated_adjustments: float = 0.0
    
    def __post_init__(self):
        if self.subscription_history is None:
            self.subscription_history = []

@dataclass
class StressTestResult:
    test_name: str
    category: str
    success: bool
    response_time: float
    status_code: int
    details: str = ""
    user_id: Optional[int] = None
    plan_change: str = ""
    price_impact: float = 0.0
    pro_rated_adjustment: float = 0.0
    pro_rated_validation_status: str = ""
    error_message: str = ""

class UserStressTestSuite:
    def __init__(self, base_url: str = "http://localhost:8080"):
        self.base_url = base_url
        self.session = requests.Session()
        self.session.headers.update({'Content-Type': 'application/json'})
        
        # Test configuration
        self.num_users = 5000
        self.users: List[UserProfile] = []
        self.available_plans: List[Dict] = []
        self.available_tiers: List[Dict] = []
        self.test_results: List[StressTestResult] = []
        
        # Performance tracking
        self.total_api_calls = 0
        self.start_time = None
        self.performance_metrics = {
            'response_times': [],
            'upgrade_times': [],
            'downgrade_times': [],
            'pricing_calculations': []
        }
        
        # Business logic tracking
        self.business_metrics = {
            'total_upgrades': 0,
            'total_downgrades': 0,
            'total_revenue': 0.0,
            'tier_transitions': {},
            'pricing_validations': 0,
            'failed_operations': 0,
            'total_pro_rated_adjustments': 0.0,
            'successful_pro_rated_validations': 0,
            'failed_pro_rated_validations': 0
        }
        
        # Indian cities and professional data for realistic users
        self.indian_cities = [
            ("Mumbai", "Maharashtra", "400001"),
            ("Delhi", "Delhi", "110001"),
            ("Bangalore", "Karnataka", "560001"),
            ("Hyderabad", "Telangana", "500001"),
            ("Chennai", "Tamil Nadu", "600001"),
            ("Kolkata", "West Bengal", "700001"),
            ("Pune", "Maharashtra", "411001"),
            ("Ahmedabad", "Gujarat", "380001"),
            ("Jaipur", "Rajasthan", "302001"),
            ("Lucknow", "Uttar Pradesh", "226001"),
            ("Kanpur", "Uttar Pradesh", "208001"),
            ("Nagpur", "Maharashtra", "440001"),
            ("Indore", "Madhya Pradesh", "452001"),
            ("Thane", "Maharashtra", "400601"),
            ("Bhopal", "Madhya Pradesh", "462001"),
            ("Visakhapatnam", "Andhra Pradesh", "530001"),
            ("Pimpri-Chinchwad", "Maharashtra", "411017"),
            ("Patna", "Bihar", "800001"),
            ("Vadodara", "Gujarat", "390001"),
            ("Ghaziabad", "Uttar Pradesh", "201001")
        ]
        
        self.first_names = [
            "Aarav", "Vivaan", "Aditya", "Vihaan", "Arjun", "Sai", "Reyansh", "Ayaan", "Krishna", "Ishaan",
            "Shaurya", "Atharv", "Advik", "Pranav", "Rudra", "Karan", "Aryan", "Harsh", "Daksh", "Kiaan",
            "Ananya", "Diya", "Aadhya", "Avni", "Pari", "Kavya", "Saanvi", "Priya", "Riya", "Tanvi",
            "Ishita", "Siya", "Aanya", "Myra", "Sara", "Nisha", "Aditi", "Pooja", "Sneha", "Deepika",
            "Rajesh", "Suresh", "Ramesh", "Mahesh", "Ganesh", "Dinesh", "Hitesh", "Rakesh", "Naresh", "Umesh"
        ]
        
        self.last_names = [
            "Sharma", "Verma", "Gupta", "Singh", "Kumar", "Agarwal", "Jain", "Mehta", "Shah", "Patel",
            "Reddy", "Rao", "Nair", "Pillai", "Iyer", "Menon", "Krishnan", "Gopal", "Srinivasan", "Raman",
            "Ahluwalia", "Malhotra", "Kapoor", "Chopra", "Sethi", "Bhatia", "Khanna", "Bansal", "Jindal", "Mittal"
        ]
        
        print("🚀 UserStressTestSuite initialized for 5000 users with 9-plan stress testing")
    
    def log_test_result(self, test_name: str, category: str, response, details: str = "",
                       user_id: int = None, plan_change: str = "", price_impact: float = 0.0,
                       pro_rated_adjustment: float = 0.0, pro_rated_validation_status: str = "",
                       error: str = ""):
        """Log detailed test result with business metrics"""
        success = 200 <= response.status_code < 300 if hasattr(response, 'status_code') else False
        response_time = getattr(response, 'elapsed', timedelta()).total_seconds() * 1000
        status_code = getattr(response, 'status_code', 0)
        
        result = StressTestResult(
            test_name=test_name,
            category=category,
            success=success,
            response_time=response_time,
            status_code=status_code,
            details=details,
            user_id=user_id,
            plan_change=plan_change,
            price_impact=price_impact,
            pro_rated_adjustment=pro_rated_adjustment,
            pro_rated_validation_status=pro_rated_validation_status,
            error_message=error
        )
        
        self.test_results.append(result)
        self.performance_metrics['response_times'].append(response_time)
        self.total_api_calls += 1
        
        # Track business metrics
        if success and price_impact > 0:
            self.business_metrics['total_revenue'] += price_impact
        elif not success:
            self.business_metrics['failed_operations'] += 1
        
        status_emoji = "✅" if success else "❌"
        if user_id and plan_change and "PLAN_" in category:
            # Always show business logic warning or validation status after each upgrade/downgrade
            print(f"{status_emoji} {test_name} | User {user_id} | {status_code} | {response_time:.0f}ms | {pro_rated_validation_status}")
        elif user_id:
            print(f"{status_emoji} {test_name} | User {user_id} | {status_code} | {response_time:.0f}ms | {plan_change}")
        else:
            print(f"{status_emoji} {test_name} | {status_code} | {response_time:.0f}ms")
    
    def generate_user_data(self, index: int) -> UserProfile:
        """Generate realistic user data"""
        city, state, pincode = random.choice(self.indian_cities)
        first_name = random.choice(self.first_names)
        last_name = random.choice(self.last_names)
        
        return UserProfile(
            name=f"{first_name} {last_name}",
            email=f"{first_name.lower()}.{last_name.lower()}.{index}@testcompany.com",
            phone=f"9{random.randint(100000000, 999999999)}",
            address=f"{random.randint(101, 999)}, {random.choice(['MG Road', 'Brigade Road', 'Commercial Street', 'Residency Road', 'Cunningham Road'])}",
            city=city,
            state=state,
            pincode=pincode
        )
    
    def wait_for_application(self, max_retries: int = 30) -> bool:
        """Wait for application to be ready"""
        print("⏳ Waiting for application to be ready...")
        for i in range(max_retries):
            try:
                response = self.session.get(f"{self.base_url}/actuator/health", timeout=5)
                if response.status_code == 200:
                    health_data = response.json()
                    if health_data.get('status') == 'UP':
                        print("✅ Application is ready!")
                        return True
            except Exception:
                pass
            
            if i % 5 == 0:
                print(f"⏳ Still waiting... ({i+1}/{max_retries})")
            time.sleep(2)
        
        print("❌ Application failed to start or is not responding")
        return False
    
    def load_system_data(self):
        """Load tiers and plans from the system"""
        print("\n📋 Loading system data...")
        
        # Load tiers
        try:
            response = self.session.get(f"{self.base_url}/api/v1/membership/tiers")
            if response.status_code == 200:
                self.available_tiers = response.json()
                print(f"✅ Loaded {len(self.available_tiers)} tiers: {[t['name'] for t in self.available_tiers]}")
            else:
                print(f"❌ Failed to load tiers: {response.status_code}")
        except Exception as e:
            print(f"❌ Error loading tiers: {str(e)}")
        
        # Load plans
        try:
            response = self.session.get(f"{self.base_url}/api/v1/membership/plans")
            if response.status_code == 200:
                self.available_plans = response.json()
                print(f"✅ Loaded {len(self.available_plans)} plans")
                
                # Display plan details for verification
                print("📋 Available Plans:")
                for plan in self.available_plans:
                    tier_name = plan.get('tier', 'Unknown')
                    plan_type = plan.get('type', 'Unknown')
                    price = plan.get('price', 0)
                    duration = plan.get('durationInMonths', 0)
                    print(f"   Plan {plan['id']}: {tier_name} {plan_type} - ₹{price} ({duration} months)")
            else:
                print(f"❌ Failed to load plans: {response.status_code}")
        except Exception as e:
            print(f"❌ Error loading plans: {str(e)}")
    
    def create_users_batch(self, start_index: int, batch_size: int) -> List[UserProfile]:
        """Create a batch of users"""
        batch_users = []
        
        for i in range(start_index, min(start_index + batch_size, self.num_users)):
            user_data = self.generate_user_data(i)
            
            try:
                user_payload = {
                    "name": user_data.name,
                    "email": user_data.email,
                    "phoneNumber": user_data.phone,
                    "address": user_data.address,
                    "city": user_data.city,
                    "state": user_data.state,
                    "pincode": user_data.pincode
                }
                
                response = self.session.post(f"{self.base_url}/api/v1/users", json=user_payload)
                
                if response.status_code == 201:
                    user_response = response.json()
                    user_data.id = user_response['id']
                    batch_users.append(user_data)
                    
                    self.log_test_result(
                        f"User Creation {i+1}", "USER_CREATION", response,
                        f"Created user: {user_data.name}", user_id=user_data.id
                    )
                else:
                    self.log_test_result(
                        f"User Creation {i+1}", "USER_CREATION", response,
                        f"Failed to create user: {user_data.name}",
                        error=f"Status: {response.status_code}"
                    )
                
            except Exception as e:
                print(f"❌ Error creating user {i+1}: {str(e)}")
        
        return batch_users
    
    def create_all_users(self):
        """Create all 2000 users using batch processing"""
        print(f"\n👥 Creating {self.num_users} users...")
        
        batch_size = 50  # Process in batches to avoid overwhelming the server
        
        for start_idx in range(0, self.num_users, batch_size):
            batch_users = self.create_users_batch(start_idx, batch_size)
            self.users.extend(batch_users)
            
            if start_idx % 200 == 0:
                print(f"📊 Progress: {len(self.users)}/{self.num_users} users created")
            
            # Small delay between batches
            time.sleep(0.1)
        
        print(f"✅ Successfully created {len(self.users)} users")
    
    def subscribe_all_users_to_random_plans(self):
        """Subscribe all users to random plans"""
        print(f"\n📊 Subscribing all {len(self.users)} users to random plans...")
        
        if not self.available_plans:
            print("❌ No plans available for subscription")
            return
        
        successful_subscriptions = 0
        
        for i, user in enumerate(self.users):
            if not user.id:
                continue
            
            # Select a random plan
            plan = random.choice(self.available_plans)
            
            subscription_data = {
                "userId": user.id,
                "planId": plan['id'],
                "autoRenewal": random.choice([True, False])
            }
            
            try:
                response = self.session.post(f"{self.base_url}/api/v1/subscriptions", json=subscription_data)
                
                if response.status_code == 201:
                    subscription_response = response.json()
                    user.current_subscription_id = subscription_response['id']
                    user.current_plan_id = plan['id']
                    user.current_tier = plan.get('tier', 'Unknown')
                    user.total_price_paid += float(plan.get('price', 0))
                    
                    # Add to subscription history
                    user.subscription_history.append({
                        'action': 'INITIAL_SUBSCRIPTION',
                        'plan_id': plan['id'],
                        'tier': plan.get('tier'),
                        'type': plan.get('type'),
                        'price': plan.get('price'),
                        'timestamp': datetime.now().isoformat()
                    })
                    
                    successful_subscriptions += 1
                    
                    self.log_test_result(
                        f"Initial Subscription {i+1}", "INITIAL_SUBSCRIPTION", response,
                        f"{user.name} → {plan.get('tier')} {plan.get('type')}",
                        user_id=user.id,
                        plan_change=f"New → {plan.get('tier')} {plan.get('type')}",
                        price_impact=float(plan.get('price', 0))
                    )
                else:
                    self.log_test_result(
                        f"Initial Subscription {i+1}", "INITIAL_SUBSCRIPTION", response,
                        f"Failed subscription for {user.name}",
                        user_id=user.id,
                        error=f"Status: {response.status_code}"
                    )
                
            except Exception as e:
                print(f"❌ Error subscribing user {user.id}: {str(e)}")
            
            if i % 100 == 0:
                print(f"📊 Progress: {successful_subscriptions}/{i+1} successful subscriptions")
        
        print(f"✅ Successfully created {successful_subscriptions} subscriptions")
    
    def calculate_tier_hierarchy_score(self, tier: str) -> int:
        """Calculate tier hierarchy score for business logic validation"""
        tier_scores = {
            'SILVER': 1,
            'GOLD': 2,
            'PLATINUM': 3
        }
        return tier_scores.get(tier, 0)
    
    def calculate_plan_value_score(self, plan: Dict) -> float:
        """Calculate plan value score based on tier and duration"""
        tier_score = self.calculate_tier_hierarchy_score(plan.get('tier', ''))
        duration_months = plan.get('durationInMonths', 1)
        price = float(plan.get('price', 0))
        
        # Higher tier, longer duration = higher value
        # But also consider price efficiency
        base_score = tier_score * 10 + duration_months
        price_efficiency = price / (duration_months * tier_score) if (duration_months * tier_score) > 0 else 999999
        
        return base_score - (price_efficiency / 100)  # Lower price efficiency is better
    
    def is_upgrade(self, from_plan: Dict, to_plan: Dict) -> bool:
        """Determine if plan change is an upgrade"""
        from_score = self.calculate_plan_value_score(from_plan)
        to_score = self.calculate_plan_value_score(to_plan)
        return to_score > from_score
    
    def calculate_expected_pro_rated_adjustment(self, from_plan: Dict, to_plan: Dict, current_paid_amount: float) -> float:
        """Calculate expected pro-rated adjustment for business logic validation"""
        from_price = float(from_plan.get('price', 0))
        to_price = float(to_plan.get('price', 0))
        from_duration = from_plan.get('durationInMonths', 1)
        to_duration = to_plan.get('durationInMonths', 1)
        
        # Simple pro-rated calculation for validation
        # This is a simplified version - actual business logic may be more complex
        price_difference = to_price - from_price
        
        # For upgrades, user pays the difference
        # For downgrades, user gets credit
        if price_difference > 0:  # Upgrade
            # Assume user pays proportional amount based on remaining time
            remaining_time_factor = 0.7  # Assume 70% of subscription time remaining
            expected_adjustment = price_difference * remaining_time_factor
        else:  # Downgrade
            # User gets credit for the difference
            expected_adjustment = price_difference * 0.5  # 50% credit for simplicity
        
        return expected_adjustment
    
    def perform_intensive_plan_changes(self):
        """Perform intensive upgrade/downgrade operations on all users"""
        print(f"\n🔄 Performing intensive plan changes across all {len(self.users)} users...")
        
        if not self.available_plans or len(self.available_plans) < 9:
            print(f"❌ Need at least 9 plans, found {len(self.available_plans)}")
            return
        
        # Each user will perform multiple plan changes
        changes_per_user = 5  # Each user will change plans 5 times
        total_changes = len(self.users) * changes_per_user
        
        print(f"🎯 Target: {total_changes} total plan changes ({changes_per_user} per user)")
        
        change_count = 0
        
        for user_idx, user in enumerate(self.users):
            if not user.current_subscription_id or not user.current_plan_id:
                continue
            
            current_plan = next((p for p in self.available_plans if p['id'] == user.current_plan_id), None)
            if not current_plan:
                continue
            
            user_changes = 0
            
            # Perform multiple plan changes for this user
            for change_idx in range(changes_per_user):
                # Select a different plan (ensure it's different from current)
                available_plans_for_change = [p for p in self.available_plans if p['id'] != user.current_plan_id]
                if not available_plans_for_change:
                    continue
                
                # Strategy: Make it challenging by sometimes forcing difficult transitions
                if change_idx % 2 == 0:
                    # Force cross-tier changes (more challenging)
                    different_tier_plans = [p for p in available_plans_for_change 
                                          if p.get('tier') != current_plan.get('tier')]
                    target_plan = random.choice(different_tier_plans if different_tier_plans else available_plans_for_change)
                else:
                    # Random plan change
                    target_plan = random.choice(available_plans_for_change)
                
                # Determine if upgrade or downgrade
                is_upgrade_operation = self.is_upgrade(current_plan, target_plan)
                operation_type = "UPGRADE" if is_upgrade_operation else "DOWNGRADE"
                
                # Calculate price difference for business validation
                current_price = float(current_plan.get('price', 0))
                target_price = float(target_plan.get('price', 0))
                price_difference = target_price - current_price
                
                # Prepare update data
                update_data = {
                    "planId": target_plan['id'],
                    "autoRenewal": random.choice([True, False])
                }
                
                try:
                    # Get current subscription details before update for pro-rated validation
                    pre_response = self.session.get(f"{self.base_url}/api/v1/subscriptions/{user.current_subscription_id}")
                    pre_paid_amount = 0.0
                    if pre_response.status_code == 200:
                        pre_paid_amount = float(pre_response.json().get('paidAmount', 0))
                    
                    start_time = time.time()
                    response = self.session.put(
                        f"{self.base_url}/api/v1/subscriptions/{user.current_subscription_id}",
                        json=update_data
                    )
                    end_time = time.time()
                    
                    response_time = (end_time - start_time) * 1000
                    
                    # Calculate and validate pro-rated adjustments
                    pro_rated_adjustment = 0.0
                    pro_rated_validation_status = "❌ Price calculation failed"
                    
                    if response.status_code == 200:
                        response_data = response.json()
                        post_paid_amount = float(response_data.get('paidAmount', 0))
                        pro_rated_adjustment = post_paid_amount - pre_paid_amount
                        
                        # Business logic validation for pro-rated billing
                        expected_adjustment = self.calculate_expected_pro_rated_adjustment(
                            current_plan, target_plan, pre_paid_amount
                        )
                        
                        tolerance = 1.0  # ₹1 tolerance for rounding differences
                        adjustment_difference = abs(pro_rated_adjustment - expected_adjustment)
                        
                        if adjustment_difference <= tolerance:
                            pro_rated_validation_status = f"✅ Pro-rated validation PASSED | Expected: ₹{expected_adjustment:.2f} | Actual: ₹{pro_rated_adjustment:.2f}"
                            self.business_metrics['successful_pro_rated_validations'] += 1
                        else:
                            pro_rated_validation_status = f"⚠️ BUSINESS LOGIC WARNING: Price calculation mismatch | Expected: ₹{expected_adjustment:.2f} | Actual: ₹{pro_rated_adjustment:.2f} | Diff: ₹{adjustment_difference:.2f}"
                            self.business_metrics['failed_pro_rated_validations'] += 1
                        
                        self.business_metrics['pricing_validations'] += 1
                        
                        # Track pro-rated adjustments
                        user.total_pro_rated_adjustments += pro_rated_adjustment
                        self.business_metrics['total_pro_rated_adjustments'] += pro_rated_adjustment
                        
                        # Update user's current plan
                        user.current_plan_id = target_plan['id']
                        user.current_tier = target_plan.get('tier', 'Unknown')
                        user.total_price_paid += max(0, price_difference)  # Only add if upgrade
                        
                        # Update counters
                        if is_upgrade_operation:
                            user.total_upgrades += 1
                            self.business_metrics['total_upgrades'] += 1
                            if response_time > 0:
                                self.performance_metrics['upgrade_times'].append(response_time)
                        else:
                            user.total_downgrades += 1
                            self.business_metrics['total_downgrades'] += 1
                            if response_time > 0:
                                self.performance_metrics['downgrade_times'].append(response_time)
                        
                        # Track tier transitions
                        transition_key = f"{current_plan.get('tier')} → {target_plan.get('tier')}"
                        self.business_metrics['tier_transitions'][transition_key] = \
                            self.business_metrics['tier_transitions'].get(transition_key, 0) + 1
                        
                        # Add to subscription history with pro-rated info
                        user.subscription_history.append({
                            'action': operation_type,
                            'from_plan_id': current_plan['id'],
                            'to_plan_id': target_plan['id'],
                            'from_tier': current_plan.get('tier'),
                            'to_tier': target_plan.get('tier'),
                            'from_type': current_plan.get('type'),
                            'to_type': target_plan.get('type'),
                            'price_difference': price_difference,
                            'pro_rated_adjustment': pro_rated_adjustment,
                            'timestamp': datetime.now().isoformat()
                        })
                        
                        plan_change_desc = f"{current_plan.get('tier')} {current_plan.get('type')} → {target_plan.get('tier')} {target_plan.get('type')}"
                        
                        self.log_test_result(
                            f"{operation_type} {change_count+1}", f"PLAN_{operation_type}", response,
                            f"{user.name}: {plan_change_desc} | {pro_rated_validation_status}",
                            user_id=user.id,
                            plan_change=plan_change_desc,
                            price_impact=abs(price_difference),  # Show absolute value for impact
                            pro_rated_adjustment=pro_rated_adjustment,
                            pro_rated_validation_status=pro_rated_validation_status
                        )
                        
                        # Update current plan for next iteration
                        current_plan = target_plan
                        user_changes += 1
                        change_count += 1
                        
                    else:
                        pro_rated_validation_status = f"❌ {operation_type} FAILED: {current_plan.get('tier')} {current_plan.get('type')} → {target_plan.get('tier')} {target_plan.get('type')} | Status: {response.status_code}"
                        self.business_metrics['failed_pro_rated_validations'] += 1
                        self.log_test_result(
                            f"{operation_type} {change_count+1}", f"PLAN_{operation_type}", response,
                            f"Failed {operation_type.lower()} for {user.name} | {pro_rated_validation_status}",
                            user_id=user.id,
                            error=f"Status: {response.status_code}"
                        )
                
                except Exception as e:
                    print(f"❌ Error changing plan for user {user.id}: {str(e)}")
                
                # Small delay between changes for the same user
                time.sleep(0.01)
            
            # Progress reporting
            if user_idx % 100 == 0:
                success_rate = (change_count / max(1, user_idx * changes_per_user)) * 100
                print(f"📊 Progress: User {user_idx+1}/{len(self.users)} | Changes: {change_count}/{total_changes} | Success: {success_rate:.1f}%")
        
        print(f"✅ Completed {change_count} plan changes")
    
    def perform_concurrent_stress_operations(self):
        """Perform concurrent operations to stress test the system"""
        print(f"\n⚡ Performing concurrent stress operations...")
        
        def concurrent_operation_worker(worker_id: int, users_batch: List[UserProfile]):
            """Worker that performs concurrent operations on a batch of users"""
            worker_results = []
            
            for user in users_batch:
                if not user.current_subscription_id:
                    continue
                
                # Perform random operations
                operations = [
                    # Get subscription details
                    lambda: self.session.get(f"{self.base_url}/api/v1/subscriptions/{user.current_subscription_id}"),
                    # Get user details
                    lambda: self.session.get(f"{self.base_url}/api/v1/users/{user.id}"),
                    # Get all subscriptions
                    lambda: self.session.get(f"{self.base_url}/api/v1/subscriptions"),
                    # Get all plans
                    lambda: self.session.get(f"{self.base_url}/api/v1/membership/plans"),
                ]
                
                for op_idx, operation in enumerate(operations):
                    try:
                        start_time = time.time()
                        response = operation()
                        end_time = time.time()
                        
                        response_time = (end_time - start_time) * 1000
                        
                        result = StressTestResult(
                            test_name=f"Concurrent Op Worker {worker_id} User {user.id} Op {op_idx+1}",
                            category="CONCURRENT_OPERATIONS",
                            success=200 <= response.status_code < 300,
                            response_time=response_time,
                            status_code=response.status_code,
                            details=f"Worker {worker_id} concurrent operation",
                            user_id=user.id
                        )
                        worker_results.append(result)
                        
                    except Exception as e:
                        error_result = StressTestResult(
                            test_name=f"Concurrent Op Worker {worker_id} User {user.id} Op {op_idx+1}",
                            category="CONCURRENT_OPERATIONS",
                            success=False,
                            response_time=0,
                            status_code=0,
                            details=f"Worker {worker_id} operation failed",
                            user_id=user.id,
                            error_message=str(e)
                        )
                        worker_results.append(error_result)
            
            return worker_results
        
        # Split users into batches for concurrent processing
        num_workers = 20
        users_per_worker = len(self.users) // num_workers
        
        with concurrent.futures.ThreadPoolExecutor(max_workers=num_workers) as executor:
            futures = []
            
            for worker_id in range(num_workers):
                start_idx = worker_id * users_per_worker
                end_idx = start_idx + users_per_worker if worker_id < num_workers - 1 else len(self.users)
                users_batch = self.users[start_idx:end_idx]
                
                future = executor.submit(concurrent_operation_worker, worker_id, users_batch)
                futures.append(future)
            
            # Collect results
            all_concurrent_results = []
            for future in concurrent.futures.as_completed(futures):
                try:
                    worker_results = future.result()
                    all_concurrent_results.extend(worker_results)
                except Exception as e:
                    print(f"❌ Concurrent worker failed: {str(e)}")
        
        # Add results to main test results
        self.test_results.extend(all_concurrent_results)
        
        print(f"⚡ Completed {len(all_concurrent_results)} concurrent operations")
    
    def generate_comprehensive_report(self):
        """Generate comprehensive test report"""
        end_time = time.time()
        duration = end_time - self.start_time
        
        print("\n" + "=" * 100)
        print("🏆 USER STRESS TESTING SUITE - COMPREHENSIVE REPORT")
        print("=" * 100)
        
        # Overall statistics
        total_tests = len(self.test_results)
        passed_tests = len([r for r in self.test_results if r.success])
        failed_tests = total_tests - passed_tests
        success_rate = (passed_tests / total_tests * 100) if total_tests > 0 else 0
        
        print(f"⏱️  Total Duration: {duration:.2f} seconds ({duration/60:.1f} minutes)")
        print(f"👥 Users Created: {len(self.users)}")
        print(f"🎯 Total Tests: {total_tests}")
        print(f"✅ Passed: {passed_tests}")
        print(f"❌ Failed: {failed_tests}")
        print(f"📈 Overall Success Rate: {success_rate:.1f}%")
        print(f"🔥 Total API Calls: {self.total_api_calls}")
        
        # Performance metrics
        if self.performance_metrics['response_times']:
            avg_response = statistics.mean(self.performance_metrics['response_times'])
            min_response = min(self.performance_metrics['response_times'])
            max_response = max(self.performance_metrics['response_times'])
            
            print(f"\n⚡ Performance Metrics:")
            print(f"  Average Response Time: {avg_response:.2f}ms")
            print(f"  Fastest Response: {min_response:.2f}ms")
            print(f"  Slowest Response: {max_response:.2f}ms")
        
        # Upgrade/Downgrade performance
        if self.performance_metrics['upgrade_times']:
            avg_upgrade_time = statistics.mean(self.performance_metrics['upgrade_times'])
            print(f"  Average Upgrade Time: {avg_upgrade_time:.2f}ms")
        
        if self.performance_metrics['downgrade_times']:
            avg_downgrade_time = statistics.mean(self.performance_metrics['downgrade_times'])
            print(f"  Average Downgrade Time: {avg_downgrade_time:.2f}ms")
        
        # Business metrics
        print(f"\n💼 Business Logic Metrics:")
        print(f"  Total Upgrades: {self.business_metrics['total_upgrades']}")
        print(f"  Total Downgrades: {self.business_metrics['total_downgrades']}")
        print(f"  Total Revenue Impact: ₹{self.business_metrics['total_revenue']:.2f}")
        print(f"  Pricing Validations: {self.business_metrics['pricing_validations']}")
        print(f"  Failed Operations: {self.business_metrics['failed_operations']}")
        
        # Pro-rated billing metrics with enhanced details
        print(f"\n💰 Pro-rated Billing Validation:")
        print(f"  Total Pro-rated Adjustments: ₹{self.business_metrics['total_pro_rated_adjustments']:.2f}")
        print(f"  Successful Validations: {self.business_metrics['successful_pro_rated_validations']}")
        print(f"  Failed Validations: {self.business_metrics['failed_pro_rated_validations']}")
        total_validations = self.business_metrics['successful_pro_rated_validations'] + self.business_metrics['failed_pro_rated_validations']
        if total_validations > 0:
            validation_success_rate = (self.business_metrics['successful_pro_rated_validations'] / total_validations) * 100
            print(f"  Validation Success Rate: {validation_success_rate:.1f}%")
        
        # Calculate total revenue from all plan changes
        total_plan_changes = self.business_metrics['total_upgrades'] + self.business_metrics['total_downgrades']
        if total_plan_changes > 0:
            avg_pro_rated_per_change = self.business_metrics['total_pro_rated_adjustments'] / total_plan_changes
            print(f"  Average Pro-rated per Change: ₹{avg_pro_rated_per_change:.2f}")
        
        # Show pricing breakdown by plan types
        upgrade_results = [r for r in self.test_results if r.category == "PLAN_UPGRADE" and r.success]
        downgrade_results = [r for r in self.test_results if r.category == "PLAN_DOWNGRADE" and r.success]
        
        if upgrade_results:
            total_upgrade_adjustments = sum(r.pro_rated_adjustment for r in upgrade_results if r.pro_rated_adjustment > 0)
            print(f"  Total Upgrade Pro-rated Revenue: +₹{total_upgrade_adjustments:.2f}")
        
        if downgrade_results:
            total_downgrade_credits = sum(abs(r.pro_rated_adjustment) for r in downgrade_results if r.pro_rated_adjustment < 0)
            print(f"  Total Downgrade Credits Issued: -₹{total_downgrade_credits:.2f}")
        
        # Net pro-rated impact
        net_pro_rated_impact = self.business_metrics['total_pro_rated_adjustments']
        print(f"  Net Pro-rated Impact: ₹{net_pro_rated_impact:+.2f}")
        
        if net_pro_rated_impact > 0:
            print(f"  📈 System generated net positive revenue from pro-rated billing")
        elif net_pro_rated_impact < 0:
            print(f"  📉 System issued net credits through pro-rated billing")
        else:
            print(f"  ⚖️ Pro-rated billing is perfectly balanced")
        
        # Tier transition analysis
        if self.business_metrics['tier_transitions']:
            print(f"\n🔄 Tier Transition Analysis:")
            for transition, count in sorted(self.business_metrics['tier_transitions'].items()):
                print(f"  {transition}: {count} transitions")
        
        # User statistics
        users_with_upgrades = len([u for u in self.users if u.total_upgrades > 0])
        users_with_downgrades = len([u for u in self.users if u.total_downgrades > 0])
        
        # Calculate averages safely
        users_with_price = [u.total_price_paid for u in self.users if u.total_price_paid > 0]
        avg_price_per_user = statistics.mean(users_with_price) if users_with_price else 0
        
        users_with_pro_rated = [u.total_pro_rated_adjustments for u in self.users if u.total_pro_rated_adjustments != 0]
        avg_pro_rated_per_user = statistics.mean(users_with_pro_rated) if users_with_pro_rated else 0
        
        print(f"\n👤 User Statistics:")
        print(f"  Users with Upgrades: {users_with_upgrades}")
        print(f"  Users with Downgrades: {users_with_downgrades}")
        print(f"  Average Price per User: ₹{avg_price_per_user:.2f}")
        print(f"  Average Pro-rated Adjustment per User: ₹{avg_pro_rated_per_user:.2f}")
        
        # Categorized results
        categories = {}
        for result in self.test_results:
            if result.category not in categories:
                categories[result.category] = {'total': 0, 'passed': 0}
            categories[result.category]['total'] += 1
            if result.success:
                categories[result.category]['passed'] += 1
        
        print(f"\n📊 Results by Category:")
        for category, stats in categories.items():
            success_rate_cat = (stats['passed'] / stats['total'] * 100) if stats['total'] > 0 else 0
            print(f"  {category}: {stats['passed']}/{stats['total']} ({success_rate_cat:.1f}%)")
        
        # System assessment
        if success_rate >= 95:
            assessment = "🌟 OUTSTANDING: System handles massive user load excellently!"
        elif success_rate >= 90:
            assessment = "✅ EXCELLENT: System performs very well under heavy user stress"
        elif success_rate >= 85:
            assessment = "👍 GOOD: System handles user stress well with minor issues"
        elif success_rate >= 75:
            assessment = "⚠️  FAIR: System functional but needs optimization for scale"
        else:
            assessment = "❌ POOR: System requires significant improvements for user scale"
        
        print(f"\n🏆 User Stress Testing Assessment:")
        print(f"{assessment}")
        
        print("=" * 100)
        print("🎯 USER STRESS TESTING COMPLETE")
        print("=" * 100)
        
        return success_rate >= 90
    
    def run_user_stress_test_suite(self):
        """Run the complete user stress test suite"""
        self.start_time = time.time()
        
        print("🚀 FirstClub Membership Program - USER STRESS TESTING SUITE")
        print("=" * 100)
        print(f"🎯 Target: {self.num_users} users with intensive plan operations")
        print(f"📊 Plan Operations: All users subscribe → Random plan changes → Upgrades/Downgrades")
        print(f"💼 Business Logic: Price calculations, tier validations, subscription history")
        print(f"⚡ Stress Testing: Concurrent operations, performance monitoring")
        print()
        
        # Wait for application
        if not self.wait_for_application():
            print("❌ Cannot proceed without ready application")
            return False
        
        # Load system data
        self.load_system_data()
        
        if len(self.available_plans) < 9:
            print(f"❌ Need at least 9 plans for stress testing, found {len(self.available_plans)}")
            return False
        
        # Phase 1: Create all users
        print("\n🎯 PHASE 1: Mass User Creation")
        self.create_all_users()
        
        # Phase 2: Subscribe all users to random plans
        print("\n🎯 PHASE 2: Mass Subscription Creation")
        self.subscribe_all_users_to_random_plans()
        
        # Phase 3: Intensive plan changes (upgrades/downgrades)
        print("\n🎯 PHASE 3: Intensive Plan Change Operations")
        self.perform_intensive_plan_changes()
        
        # Phase 4: Concurrent stress operations
        print("\n🎯 PHASE 4: Concurrent Stress Operations")
        self.perform_concurrent_stress_operations()
        
        # Generate comprehensive report
        success = self.generate_comprehensive_report()
        
        return success

if __name__ == "__main__":
    print("🔥 Initializing User Stress Testing Suite for 5000 users...")
    tester = UserStressTestSuite()
    
    try:
        success = tester.run_user_stress_test_suite()
        exit_code = 0 if success else 1
        
        print(f"\n🏁 User stress test suite completed with exit code: {exit_code}")
        if success:
            print("🎉 System successfully handled 5000 users with intensive operations!")
        else:
            print("⚠️  System needs improvements for handling large user loads")
        
        sys.exit(exit_code)
        
    except KeyboardInterrupt:
        print("\n⚠️  User stress test suite interrupted by user")
        sys.exit(1)
    except Exception as e:
        print(f"\n❌ User stress test suite failed with exception: {str(e)}")
        import traceback
        traceback.print_exc()
        sys.exit(1)
