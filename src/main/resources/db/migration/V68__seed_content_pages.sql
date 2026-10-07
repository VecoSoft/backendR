-- V68: first real content for the public FAQ, Terms of use and Privacy policy pages (English + Bangla).
-- Admins edit them later in System -> Content; a page an admin already saved is never overwritten.
-- FAQ sections use fixed "## " headings that the app links to: Account, Orders, Bookings, Listings,
-- Reviews, Payments (and their Bangla equivalents).

INSERT INTO content_page (slug, locale, title, body_md, version)
VALUES
('faq', 'en', 'Frequently asked questions', $md$
## Account

**How do I sign up?**
Tap **Sign up**, enter your mobile number and the one-time code we send by SMS, then choose a password. One number can have a personal account and a business account, and you can switch between them from the account menu.

**I forgot my password. What now?**
On the login screen tap **Forgot password**, enter your number and the SMS code, and set a new password. If you no longer have that number, contact support from Help.

## Orders

**How do I order from a restaurant or shop?**
Open the business page, add items from the menu and tap **Checkout**. Choose pickup or delivery (if the business delivers to you) and confirm. You can follow the order under **Orders & bookings**.

**Can I cancel an order?**
You can cancel while the order is still waiting for the business to accept it. Once accepted, contact the business through chat, or contact support if something went wrong.

## Bookings

**How do bookings work?**
On a salon, clinic or other service page tap **Book**, pick a service, a staff member if offered, and a free time slot. The business confirms the booking and you get a notification.

**What if I can't make it?**
Cancel from **Orders & bookings** as early as you can so someone else can take the slot. Repeated no-shows may limit your future bookings.

## Listings

**How do I add or claim my business?**
Switch to a business account and tap **Add a business**, or open your existing listing and tap **Claim this business**. We may verify ownership by phone or document.

**Why are some of my changes "waiting for approval"?**
On a verified listing, changes to the name, phone, address or category are checked by our team first so customers aren't misled. Everything else updates immediately, and you can cancel a pending change any time.

## Reviews

**Who can write a review?**
Anyone with an account can review a business they have used. Reviews must be honest, first-hand and respectful — no paid, fake or copied reviews.

**A review about my business is unfair. What can I do?**
Reply publicly from your owner dashboard, or tap **Report** on the review if it breaks the rules. Our moderators check every report; we don't remove reviews just because they are negative.

## Payments

**How do I pay for orders and bookings?**
Today you pay the business directly — cash on delivery or at the business. Jachai doesn't take card payments for orders yet.

**How do businesses pay for boosts?**
Boosts are paid by bKash, Nagad or manual transfer. Enter the transaction reference when you buy; the boost starts once our team verifies the payment.
$md$, 1),

('faq', 'bn', 'সাধারণ প্রশ্নোত্তর', $md$
## অ্যাকাউন্ট

**কিভাবে সাইন আপ করব?**
**সাইন আপ** চাপুন, আপনার মোবাইল নম্বর ও SMS-এ পাঠানো কোড দিন, তারপর পাসওয়ার্ড ঠিক করুন। একই নম্বরে একটি ব্যক্তিগত ও একটি ব্যবসায়িক অ্যাকাউন্ট রাখা যায়, অ্যাকাউন্ট মেনু থেকে বদলানো যায়।

**পাসওয়ার্ড ভুলে গেছি, কী করব?**
লগইন পেজে **পাসওয়ার্ড ভুলে গেছেন** চাপুন, নম্বর ও SMS কোড দিয়ে নতুন পাসওয়ার্ড দিন। নম্বরটি আর না থাকলে সাহায্য পেজ থেকে সাপোর্টে যোগাযোগ করুন।

## অর্ডার

**রেস্টুরেন্ট বা দোকান থেকে কিভাবে অর্ডার করব?**
ব্যবসার পেজ খুলে মেনু থেকে আইটেম যোগ করুন, তারপর **চেকআউট** চাপুন। পিকআপ বা ডেলিভারি (ব্যবসাটি আপনার এলাকায় দিলে) বেছে নিশ্চিত করুন। **অর্ডার ও বুকিং**-এ অর্ডারের অবস্থা দেখতে পাবেন।

**অর্ডার কি বাতিল করা যায়?**
ব্যবসা অর্ডার গ্রহণ করার আগ পর্যন্ত বাতিল করা যায়। গ্রহণের পর চ্যাটে ব্যবসার সাথে কথা বলুন, সমস্যা হলে সাপোর্টে জানান।

## বুকিং

**বুকিং কিভাবে কাজ করে?**
সেলুন, ক্লিনিক বা অন্য সার্ভিসের পেজে **বুক করুন** চাপুন, সার্ভিস, (থাকলে) স্টাফ ও খালি সময় বেছে নিন। ব্যবসা নিশ্চিত করলে নোটিফিকেশন পাবেন।

**সময়মতো যেতে না পারলে?**
যত আগে পারেন **অর্ডার ও বুকিং** থেকে বাতিল করুন, যাতে অন্য কেউ সময়টি নিতে পারে। বারবার না গেলে ভবিষ্যতের বুকিং সীমিত হতে পারে।

## লিস্টিং

**আমার ব্যবসা কিভাবে যোগ বা দাবি করব?**
ব্যবসায়িক অ্যাকাউন্টে গিয়ে **ব্যবসা যোগ করুন** চাপুন, অথবা আগের লিস্টিং খুলে **এই ব্যবসা দাবি করুন** চাপুন। মালিকানা যাচাইয়ে আমরা ফোন বা কাগজপত্র চাইতে পারি।

**কিছু পরিবর্তন কেন "অনুমোদনের অপেক্ষায়" দেখায়?**
যাচাইকৃত লিস্টিংয়ে নাম, ফোন, ঠিকানা বা ক্যাটাগরি বদলালে গ্রাহকদের বিভ্রান্তি এড়াতে আমাদের টিম আগে দেখে নেয়। বাকি সব সাথে সাথে বদলায়, আর অপেক্ষমাণ পরিবর্তন যেকোনো সময় বাতিল করা যায়।

## রিভিউ

**কে রিভিউ লিখতে পারে?**
অ্যাকাউন্ট থাকা যে কেউ, যে ব্যবসার সেবা নিয়েছেন সেটির রিভিউ দিতে পারেন। রিভিউ সৎ, নিজের অভিজ্ঞতা ও সম্মানজনক হতে হবে — টাকা নিয়ে, ভুয়া বা কপি করা রিভিউ চলবে না।

**আমার ব্যবসা নিয়ে একটি রিভিউ অন্যায্য, কী করব?**
মালিকের ড্যাশবোর্ড থেকে প্রকাশ্যে উত্তর দিন, অথবা নিয়ম ভাঙলে রিভিউতে **রিপোর্ট** চাপুন। প্রতিটি রিপোর্ট মডারেটর দেখেন; শুধু নেতিবাচক বলে রিভিউ সরানো হয় না।

## পেমেন্ট

**অর্ডার ও বুকিংয়ের টাকা কিভাবে দেব?**
এখন সরাসরি ব্যবসাকে টাকা দিতে হয় — ক্যাশ অন ডেলিভারি বা ব্যবসার জায়গায়। জাচাই এখনো অর্ডারের জন্য কার্ড পেমেন্ট নেয় না।

**ব্যবসা বুস্টের টাকা কিভাবে দেয়?**
বিকাশ, নগদ বা ম্যানুয়াল ট্রান্সফারে। কেনার সময় ট্রানজেকশন রেফারেন্স দিন; আমাদের টিম পেমেন্ট যাচাই করলে বুস্ট শুরু হয়।
$md$, 1),

('terms', 'en', 'Terms of use', $md$
_These terms apply to everyone who uses Jachai — the website and the app — as a visitor, a member or a business owner._

## 1. Who we are
Jachai is an online platform in Bangladesh for discovering local businesses, reading and writing reviews, ordering, booking services and taking part in the community.

## 2. Your account
- You must give a mobile number you control and keep your password secret. You are responsible for what happens under your account.
- One person may hold one personal account and one linked business account. Don't create accounts to get around a restriction.
- You must be at least 13 years old. Some features (such as orders or bookings) may need you to be 18 or to have a guardian's permission.

## 3. Reviews and community content
- Write only about your own, genuine experience. Paid, fake, copied or incentivised reviews are not allowed — including reviews of your own business or a competitor's.
- No harassment, hate speech, threats, sexual content, personal information about others, or illegal content.
- You keep ownership of what you post, but you give Jachai a non-exclusive, royalty-free licence to show, store and share it on the platform and in promotion of the platform.
- We may hold content for review, hide it, or remove it when it breaks these rules or the law, and we may restrict or suspend accounts that repeatedly do so.

## 4. Businesses
- Business owners must give accurate details and keep them up to date. Changes to protected details of a verified listing are reviewed before they go live.
- Owners are responsible for their products, services, prices, offers, orders and bookings. Jachai is not a party to the sale.
- Boosts and other paid promotion are shown as "Sponsored". Payment is due before a boost starts; refunds are at our discretion when a boost could not run.

## 5. Orders and bookings
Orders and bookings are agreements between you and the business. Pay the business as agreed. Use chat for questions and Help → Contact support if something goes wrong — we will try to help, but the business is responsible for fulfilling your order or appointment.

## 6. Messages
Chats between customers and businesses are private. If a participant reports a conversation, our moderators can read it to decide on the report, and may warn or temporarily block a user from messaging.

## 7. Things you must not do
Don't scrape or copy the service at scale, interfere with its security, impersonate others, send spam, or use Jachai for anything unlawful.

## 8. Our service
We work to keep Jachai available and accurate but provide it "as is". We may change, pause or end features. To the extent the law allows, Jachai is not liable for indirect losses or for the acts of businesses or other users.

## 9. Ending your use
You can stop using Jachai at any time and ask us to delete your account through Help → Contact support. We may suspend or close accounts that break these terms.

## 10. Changes and contact
We may update these terms; the "Last updated" date above shows when. Continuing to use Jachai after a change means you accept it. Questions: Help → Contact support. These terms are governed by the laws of Bangladesh.
$md$, 1),

('terms', 'bn', 'ব্যবহারের শর্তাবলী', $md$
_এই শর্তাবলী জাচাই-এর ওয়েবসাইট ও অ্যাপ ব্যবহারকারী সবার জন্য প্রযোজ্য — দর্শক, সদস্য বা ব্যবসার মালিক।_

## ১. আমরা কারা
জাচাই বাংলাদেশের একটি অনলাইন প্ল্যাটফর্ম — স্থানীয় ব্যবসা খোঁজা, রিভিউ পড়া ও লেখা, অর্ডার, সার্ভিস বুকিং এবং কমিউনিটিতে অংশ নেওয়ার জন্য।

## ২. আপনার অ্যাকাউন্ট
- নিজের নিয়ন্ত্রণে থাকা মোবাইল নম্বর দিতে হবে এবং পাসওয়ার্ড গোপন রাখতে হবে। অ্যাকাউন্টে যা ঘটে তার দায় আপনার।
- একজন ব্যক্তি একটি ব্যক্তিগত ও একটি সংযুক্ত ব্যবসায়িক অ্যাকাউন্ট রাখতে পারেন। কোনো বিধিনিষেধ এড়াতে নতুন অ্যাকাউন্ট খোলা যাবে না।
- বয়স অন্তত ১৩ বছর হতে হবে। অর্ডার বা বুকিংয়ের মতো কিছু সুবিধায় ১৮ বছর বা অভিভাবকের অনুমতি লাগতে পারে।

## ৩. রিভিউ ও কমিউনিটি কনটেন্ট
- শুধু নিজের সত্যিকারের অভিজ্ঞতা লিখুন। টাকা বা সুবিধার বিনিময়ে, ভুয়া বা কপি করা রিভিউ নিষিদ্ধ — নিজের বা প্রতিযোগীর ব্যবসার রিভিউও।
- হয়রানি, ঘৃণাসূচক কথা, হুমকি, যৌন কনটেন্ট, অন্যের ব্যক্তিগত তথ্য বা বেআইনি কিছু পোস্ট করা যাবে না।
- পোস্টের মালিকানা আপনার থাকে, তবে প্ল্যাটফর্মে দেখানো, সংরক্ষণ ও শেয়ার করার জন্য জাচাইকে একটি অ-একচেটিয়া, বিনামূল্যের অনুমতি দেন।
- নিয়ম বা আইন ভাঙলে আমরা কনটেন্ট পর্যালোচনায় রাখতে, লুকাতে বা সরাতে পারি, এবং বারবার ভাঙলে অ্যাকাউন্ট সীমিত বা স্থগিত করতে পারি।

## ৪. ব্যবসা
- মালিকদের সঠিক তথ্য দিতে ও হালনাগাদ রাখতে হবে। যাচাইকৃত লিস্টিংয়ের সুরক্ষিত তথ্য বদলালে প্রকাশের আগে পর্যালোচনা হয়।
- পণ্য, সেবা, দাম, অফার, অর্ডার ও বুকিংয়ের দায় মালিকের। জাচাই বিক্রয়ের পক্ষ নয়।
- বুস্ট ও অন্যান্য পেইড প্রচার "স্পন্সরড" হিসেবে দেখানো হয়। বুস্ট শুরুর আগে পেমেন্ট দিতে হয়; বুস্ট চালানো না গেলে রিফান্ড আমাদের বিবেচনায়।

## ৫. অর্ডার ও বুকিং
অর্ডার ও বুকিং আপনার ও ব্যবসার মধ্যে চুক্তি। যেভাবে ঠিক হয়েছে সেভাবে ব্যবসাকে টাকা দিন। প্রশ্ন থাকলে চ্যাট করুন, সমস্যা হলে সাহায্য → সাপোর্টে যোগাযোগ করুন — আমরা সাহায্যের চেষ্টা করব, তবে অর্ডার বা অ্যাপয়েন্টমেন্ট পূরণের দায় ব্যবসার।

## ৬. মেসেজ
গ্রাহক ও ব্যবসার চ্যাট গোপন। কোনো অংশগ্রহণকারী কথোপকথন রিপোর্ট করলে মডারেটররা সিদ্ধান্তের জন্য তা পড়তে পারেন, এবং সতর্ক করতে বা সাময়িকভাবে মেসেজ পাঠানো বন্ধ করতে পারেন।

## ৭. যা করা যাবে না
বড় আকারে ডেটা স্ক্র্যাপ বা কপি করা, নিরাপত্তায় হস্তক্ষেপ, অন্যের পরিচয় নেওয়া, স্প্যাম পাঠানো বা বেআইনি কাজে জাচাই ব্যবহার করা যাবে না।

## ৮. আমাদের সেবা
জাচাই সচল ও নির্ভুল রাখতে আমরা চেষ্টা করি, তবে সেবাটি "যেমন আছে" ভিত্তিতে দেওয়া হয়। আমরা ফিচার বদলাতে, থামাতে বা বন্ধ করতে পারি। আইন যতটা অনুমতি দেয়, পরোক্ষ ক্ষতি বা ব্যবসা ও অন্য ব্যবহারকারীর কাজের জন্য জাচাই দায়ী নয়।

## ৯. ব্যবহার বন্ধ করা
যেকোনো সময় জাচাই ব্যবহার বন্ধ করতে পারেন এবং সাহায্য → সাপোর্টে যোগাযোগ থেকে অ্যাকাউন্ট মুছে ফেলার অনুরোধ করতে পারেন। শর্ত ভাঙলে আমরা অ্যাকাউন্ট স্থগিত বা বন্ধ করতে পারি।

## ১০. পরিবর্তন ও যোগাযোগ
আমরা এই শর্তাবলী হালনাগাদ করতে পারি; উপরের "সর্বশেষ হালনাগাদ" তারিখে তা দেখা যাবে। পরিবর্তনের পর ব্যবহার চালিয়ে গেলে আপনি তা মেনে নিচ্ছেন। প্রশ্ন থাকলে: সাহায্য → সাপোর্টে যোগাযোগ। এই শর্তাবলী বাংলাদেশের আইন অনুযায়ী পরিচালিত।
$md$, 1),

('privacy', 'en', 'Privacy policy', $md$
_This policy explains what personal information Jachai collects, why, and the choices you have._

## What we collect
- **Account details:** your mobile number, name, password (stored only as a secure hash), profile photo, preferred language and, if you join the community, your username and optional gender badge.
- **What you post:** reviews, photos, community posts and comments, reports and support requests.
- **Orders, bookings and offers:** items, times, delivery address and the contact details you give the business.
- **Messages:** your chats with businesses.
- **Usage and device data:** searches, pages viewed, approximate location if you allow it (to show nearby businesses), IP address and device/browser type, and sign-in history for security.
- **Businesses:** listing details, verification documents and payment references for boosts.

## How we use it
- To run your account and the features you use — showing reviews, passing orders and bookings to businesses, delivering notifications.
- To keep Jachai safe: detecting fake reviews, spam and abuse, moderating reported content and investigating reported conversations.
- To improve the service, for example by finding searches that return no results.
- To send service messages by SMS or in-app notification. We don't send marketing SMS without your consent.

## What others can see
Your reviews and community posts are public with your display name or community username. Your phone number is never shown publicly. When you order or book, the business sees the name, phone and address you provide for that order.

## Sharing
We don't sell your personal information. We share it only with the businesses you order from or book with, with service providers that help us run Jachai (hosting, SMS delivery) under confidentiality, and when the law requires it.

## Privacy of messages and support requests
Chats are visible only to you and the business. Jachai staff can read a conversation only after one of its participants reports it. Support request screenshots are visible only to you and Jachai staff.

## How long we keep data
We keep account data while your account is open. When you ask us to delete your account we remove or anonymise your personal information, except what we must keep for legal, security or fraud-prevention reasons. Public reviews may remain anonymised.

## Your choices
You can edit your profile, change your language, delete your own posts and reviews, and ask for a copy or deletion of your data through Help → Contact support.

## Security
Passwords are hashed, admin access uses role-based permissions with optional two-factor sign-in, and staff actions are logged. No system is perfectly secure; tell us at once if you think your account was misused.

## Children
Jachai is not intended for children under 13.

## Changes and contact
We may update this policy; the "Last updated" date above shows when. Questions or requests: Help → Contact support.
$md$, 1),

('privacy', 'bn', 'গোপনীয়তা নীতি', $md$
_জাচাই কোন ব্যক্তিগত তথ্য সংগ্রহ করে, কেন করে এবং আপনার কী বিকল্প আছে — এই নীতিতে তা ব্যাখ্যা করা হয়েছে।_

## আমরা কী সংগ্রহ করি
- **অ্যাকাউন্টের তথ্য:** মোবাইল নম্বর, নাম, পাসওয়ার্ড (শুধু নিরাপদ হ্যাশ হিসেবে সংরক্ষিত), প্রোফাইল ছবি, পছন্দের ভাষা, এবং কমিউনিটিতে যোগ দিলে ইউজারনেম ও ঐচ্ছিক জেন্ডার ব্যাজ।
- **আপনার পোস্ট:** রিভিউ, ছবি, কমিউনিটি পোস্ট ও কমেন্ট, রিপোর্ট এবং সাপোর্ট অনুরোধ।
- **অর্ডার, বুকিং ও অফার:** আইটেম, সময়, ডেলিভারির ঠিকানা এবং ব্যবসাকে দেওয়া যোগাযোগের তথ্য।
- **মেসেজ:** ব্যবসার সাথে আপনার চ্যাট।
- **ব্যবহার ও ডিভাইসের তথ্য:** সার্চ, দেখা পেজ, অনুমতি দিলে আনুমানিক অবস্থান (কাছের ব্যবসা দেখাতে), আইপি ঠিকানা, ডিভাইস/ব্রাউজারের ধরন এবং নিরাপত্তার জন্য সাইন-ইন ইতিহাস।
- **ব্যবসা:** লিস্টিংয়ের তথ্য, যাচাইয়ের কাগজপত্র এবং বুস্টের পেমেন্ট রেফারেন্স।

## আমরা কিভাবে ব্যবহার করি
- আপনার অ্যাকাউন্ট ও ব্যবহৃত ফিচার চালাতে — রিভিউ দেখানো, অর্ডার ও বুকিং ব্যবসার কাছে পৌঁছানো, নোটিফিকেশন পাঠানো।
- জাচাই নিরাপদ রাখতে: ভুয়া রিভিউ, স্প্যাম ও অপব্যবহার শনাক্ত করা, রিপোর্ট করা কনটেন্ট মডারেট করা এবং রিপোর্ট করা কথোপকথন যাচাই করা।
- সেবা উন্নত করতে, যেমন যেসব সার্চে কিছু পাওয়া যায় না তা খুঁজে বের করা।
- SMS বা অ্যাপ নোটিফিকেশনে সেবা-সংক্রান্ত বার্তা পাঠাতে। আপনার সম্মতি ছাড়া মার্কেটিং SMS পাঠাই না।

## অন্যরা কী দেখতে পায়
আপনার রিভিউ ও কমিউনিটি পোস্ট আপনার নাম বা কমিউনিটি ইউজারনেমসহ প্রকাশ্য। আপনার ফোন নম্বর কখনো প্রকাশ্যে দেখানো হয় না। অর্ডার বা বুকিং করলে ব্যবসা সেই অর্ডারের জন্য দেওয়া নাম, ফোন ও ঠিকানা দেখে।

## তথ্য শেয়ার
আমরা আপনার ব্যক্তিগত তথ্য বিক্রি করি না। শুধু যে ব্যবসা থেকে অর্ডার বা বুকিং করেন তাদের সাথে, জাচাই চালাতে সাহায্যকারী সেবাদাতাদের (হোস্টিং, SMS) সাথে গোপনীয়তার শর্তে, এবং আইন অনুযায়ী প্রয়োজন হলে শেয়ার করি।

## মেসেজ ও সাপোর্ট অনুরোধের গোপনীয়তা
চ্যাট শুধু আপনি ও ব্যবসা দেখতে পান। কোনো অংশগ্রহণকারী রিপোর্ট করার পরই জাচাই স্টাফ কথোপকথনটি পড়তে পারেন। সাপোর্ট অনুরোধের স্ক্রিনশট শুধু আপনি ও জাচাই স্টাফ দেখতে পান।

## কতদিন তথ্য রাখি
অ্যাকাউন্ট চালু থাকা পর্যন্ত তথ্য রাখি। অ্যাকাউন্ট মুছতে বললে আমরা ব্যক্তিগত তথ্য মুছে ফেলি বা পরিচয়হীন করি, তবে আইন, নিরাপত্তা বা প্রতারণা প্রতিরোধের জন্য যা রাখা জরুরি তা ছাড়া। প্রকাশ্য রিভিউ পরিচয়হীনভাবে থেকে যেতে পারে।

## আপনার বিকল্প
প্রোফাইল সম্পাদনা, ভাষা বদলানো, নিজের পোস্ট ও রিভিউ মুছে ফেলা যায়, এবং সাহায্য → সাপোর্টে যোগাযোগ থেকে আপনার তথ্যের কপি বা মুছে ফেলার অনুরোধ করা যায়।

## নিরাপত্তা
পাসওয়ার্ড হ্যাশ করে রাখা হয়, অ্যাডমিন অ্যাক্সেস রোলভিত্তিক অনুমতি ও ঐচ্ছিক টু-ফ্যাক্টর সাইন-ইন দিয়ে সুরক্ষিত, এবং স্টাফের কাজ লগ করা হয়। কোনো ব্যবস্থাই শতভাগ নিরাপদ নয়; অ্যাকাউন্টের অপব্যবহার সন্দেহ হলে সাথে সাথে জানান।

## শিশু
জাচাই ১৩ বছরের কম বয়সী শিশুদের জন্য নয়।

## পরিবর্তন ও যোগাযোগ
আমরা এই নীতি হালনাগাদ করতে পারি; উপরের "সর্বশেষ হালনাগাদ" তারিখে তা দেখা যাবে। প্রশ্ন বা অনুরোধ: সাহায্য → সাপোর্টে যোগাযোগ।
$md$, 1)
ON CONFLICT (slug, locale) DO NOTHING;

-- Drop the blank first/last lines the dollar-quoted bodies start and end with.
UPDATE content_page SET body_md = btrim(body_md, E'\n')
WHERE slug IN ('faq', 'terms', 'privacy') AND version = 1 AND updated_by IS NULL;

-- Version 1 history for every page this migration actually created.
INSERT INTO content_page_version (id, slug, locale, version, title, body_md, reason)
SELECT uuid_generate_v4(), p.slug, p.locale, p.version, p.title, p.body_md, 'Initial content (V68)'
FROM content_page p
WHERE p.slug IN ('faq', 'terms', 'privacy') AND p.version = 1 AND p.updated_by IS NULL
ON CONFLICT (slug, locale, version) DO NOTHING;
