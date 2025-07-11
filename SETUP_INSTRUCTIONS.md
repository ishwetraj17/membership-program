🚀 FirstClub Membership Program - Quick Setup

For Recruiters: How to Run This Application Locally
Developer: Shwet Raj

⚡ Prerequisites (5 minutes)
You need these installed on your system:
1. Java 17+

Download: https://adoptium.net/
Verify: Open terminal/cmd and run: java -version
Expected: Should show Java 17 or higher

2. Maven 3.6+

Download: https://maven.apache.org/download.cgi
Verify: Open terminal/cmd and run: mvn -version
Expected: Should show Maven 3.6 or higher


🚀 Running the Application (2 minutes)
Step 1: Extract & Navigate
bash# Extract the zip file you received
# Navigate to the project folder
cd membership-program
Step 2: One Command to Rule Them All
bash# This single command will build and run everything
mvn clean install && mvn spring-boot:run
Step 3: Wait for Success Message
You'll see this when it's ready:
======================================================================
🚀 FirstClub Membership Program Started Successfully!
👨‍💻 Developed by: Shwet Raj
======================================================================
📊 Swagger UI: http://localhost:8080/swagger-ui.html
🔍 H2 Console: http://localhost:8080/h2-console
💚 Health: http://localhost:8080/api/v1/membership/health
📈 Analytics: http://localhost:8080/api/v1/membership/analytics
======================================================================

🌐 Testing the Application (1 minute)
Immediately Open These URLs:
1. API Documentation & Testing

URL: http://localhost:8080/swagger-ui.html
What you'll see: Interactive API documentation with 15+ endpoints
Try this: Click on any API → "Try it out" → "Execute"

2. Database Console

URL: http://localhost:8080/h2-console
Login:

JDBC URL: jdbc:h2:mem:membershipdb
Username: sa
Password: password


What you'll see: Live database with sample users and membership data

3. System Health Check

URL: http://localhost:8080/api/v1/membership/health
What you'll see: System metrics and performance data

4. Business Analytics

URL: http://localhost:8080/api/v1/membership/analytics
What you'll see: Revenue analytics and business insights


🎯 Quick Demo Flow (5 minutes)
1. View Sample Data

Open Swagger UI: http://localhost:8080/swagger-ui.html
Go to "User Management" → GET /api/v1/users
Click "Try it out" → "Execute"
Result: See 3 pre-loaded Indian users (Karan, Ananya, Rohit)

2. Explore Membership Plans

Go to "Membership Management" → GET /api/v1/membership/plans
Click "Try it out" → "Execute"
Result: See 9 plans across 3 tiers with INR pricing

3. Create a Subscription

Go to POST /api/v1/membership/subscriptions
Click "Try it out"
Use this JSON:

json{
  "userId": 1,
  "planId": 4,
  "autoRenewal": true
}

Click "Execute"
Result: User gets Gold membership with benefits

4. Check Active Subscription

Go to GET /api/v1/membership/subscriptions/user/{userId}/active
Enter 1 as userId
Click "Execute"
Result: See user's active Gold membership with all benefits


🛑 To Stop the Application

Press: Ctrl+C in the terminal/command prompt
Or: Close the terminal window


🆘 Troubleshooting
❌ "Java version not supported"
Solution: Install Java 17+ from https://adoptium.net/
❌ "Maven command not found"
Solution: Install Maven from https://maven.apache.org/download.cgi
❌ "Port 8080 already in use"
Solution: Kill the process using port 8080
bash# Windows
netstat -ano | findstr :8080
taskkill /PID <PID_NUMBER> /F

# Mac/Linux  
lsof -ti:8080 | xargs kill -9
❌ Build fails
Solution: Clean and retry
bashmvn clean
mvn install -U

📋 What This Application Demonstrates
🏗️ System Architecture

Clean Architecture with proper separation of concerns
RESTful API Design with 15+ well-documented endpoints
Database Design with proper relationships and constraints
Indian Market Optimization (INR currency, phone validation, pincodes)

💼 Business Logic

3-Tier Membership System (Silver, Gold, Platinum)
Dynamic Pricing Engine with commitment-based savings
Subscription Lifecycle Management (create, upgrade, cancel, renew)
Real-time Analytics for business intelligence

🚀 Technical Excellence

Production-Ready Code with comprehensive error handling
Comprehensive Validation for Indian market (phone, pincode)
Health Monitoring and system metrics
Auto-initialization with sample data for easy testing


📞 Questions or Issues?
If you encounter any problems:

Check Prerequisites: Ensure Java 17+ and Maven 3.6+ are installed
Try Clean Build: Run mvn clean install again
Check Console: Look for error messages in the terminal
Alternative Ports: If 8080 is busy, the app will show the actual port


🎯 Next Steps
After testing the application:

Review the code structure in your IDE
Check the README.md for comprehensive documentation
Explore the business logic in the service layer
Examine the API design in the controller layer

This system is production-ready and demonstrates enterprise-level Java development skills with Spring Boot, JPA, and modern software architecture patterns.


Developed by Shwet Raj | Enterprise-Grade Membership Management System
Ready for production deployment with comprehensive business logic and analytics