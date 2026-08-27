-- =====================================================================
-- V21__seed_category_catalog.sql
-- Seed a broad catalogue of business categories so the "Category" picker
-- covers virtually any local business, not just the handful added by hand.
--
--  * ON CONFLICT (name) DO NOTHING  -> re-runnable, and it never disturbs
--    categories that already exist (their id, and any admin-set kind, stay).
--  * Each row carries the right `kind` (see V19) so the category-specific
--    showcase modules appear correctly:
--        RESTAURANT -> Menu
--        CLINIC     -> Services + Doctors/Staff
--        SALON      -> Services + Stylists
--        GYM        -> Membership plans + Facilities + Trainers
--        RETAIL     -> Featured products
--        GENERAL    -> Services (safe default for every other trade)
--  * Admins can still rename, re-kind, or delete any of these afterwards
--    from Admin > Reference data > Categories.
-- =====================================================================

INSERT INTO category (name, kind) VALUES
    -- ---- Food & drink -------------------------------------------------
    ('Restaurant',                         'RESTAURANT'),
    ('Fast Food',                          'RESTAURANT'),
    ('Cafe & Coffee Shop',                 'RESTAURANT'),
    ('Bakery & Pastry Shop',               'RESTAURANT'),
    ('Sweet Shop',                         'RESTAURANT'),
    ('Biryani & Kacchi House',             'RESTAURANT'),
    ('Chinese & Pan-Asian Restaurant',     'RESTAURANT'),
    ('Fine Dining Restaurant',             'RESTAURANT'),
    ('Pizza & Burger Joint',               'RESTAURANT'),
    ('Street Food & Snacks',               'RESTAURANT'),
    ('Juice Bar',                          'RESTAURANT'),
    ('Ice Cream & Dessert Parlour',        'RESTAURANT'),
    ('Tea Stall',                          'RESTAURANT'),
    ('Catering Service',                   'RESTAURANT'),
    ('Cloud Kitchen',                      'RESTAURANT'),

    -- ---- Health & medical ------------------------------------------------
    ('Hospital',                           'CLINIC'),
    ('Diagnostic Centre',                  'CLINIC'),
    ('General Practitioner Chamber',       'CLINIC'),
    ('Dental Clinic',                      'CLINIC'),
    ('Eye Hospital & Optics',              'CLINIC'),
    ('ENT Clinic',                         'CLINIC'),
    ('Orthopaedic Clinic',                 'CLINIC'),
    ('Physiotherapy Centre',               'CLINIC'),
    ('Skin & Laser Clinic',               'CLINIC'),
    ('Children''s Clinic',                 'CLINIC'),
    ('Women''s & Maternity Clinic',        'CLINIC'),
    ('Pharmacy',                           'CLINIC'),
    ('Veterinary Clinic',                  'CLINIC'),
    ('Blood Bank',                         'CLINIC'),
    ('Medical Equipment Supplier',         'CLINIC'),

    -- ---- Beauty & grooming ---------------------------------------------
    ('Beauty Parlour',                     'SALON'),
    ('Hair Salon',                         'SALON'),
    ('Barber Shop',                        'SALON'),
    ('Spa & Massage Centre',               'SALON'),
    ('Nail Studio',                        'SALON'),
    ('Bridal & Party Makeup',              'SALON'),
    ('Men''s Grooming Lounge',             'SALON'),

    -- ---- Fitness -----------------------------------------------------
    ('Gym & Fitness Centre',               'GYM'),
    ('Yoga Studio',                        'GYM'),
    ('Martial Arts & Boxing Club',         'GYM'),
    ('Dance Studio',                       'GYM'),
    ('Swimming Pool & Club',               'GYM'),
    ('CrossFit & Functional Training',     'GYM'),

    -- ---- Shops & retail --------------------------------------------------
    ('Grocery Store',                      'RETAIL'),
    ('Supermarket',                        'RETAIL'),
    ('Clothing & Fashion Store',           'RETAIL'),
    ('Fabric & Cloth Store',               'RETAIL'),
    ('Footwear Store',                     'RETAIL'),
    ('Bag & Luggage Store',                'RETAIL'),
    ('Jewellery Store',                    'RETAIL'),
    ('Watch & Eyewear Store',              'RETAIL'),
    ('Cosmetics & Perfume Store',          'RETAIL'),
    ('Mobile Phone Shop',                  'RETAIL'),
    ('Electronics & Appliance Store',      'RETAIL'),
    ('Computer & Accessories Store',       'RETAIL'),
    ('Furniture Store',                    'RETAIL'),
    ('Home Decor & Furnishing Store',      'RETAIL'),
    ('Mattress & Bedding Store',           'RETAIL'),
    ('Kitchenware & Crockery Store',       'RETAIL'),
    ('Hardware & Sanitary Store',          'RETAIL'),
    ('Paint & Hardware Store',             'RETAIL'),
    ('Tiles & Ceramics Showroom',          'RETAIL'),
    ('Bookshop & Stationery',              'RETAIL'),
    ('Toy & Gift Shop',                    'RETAIL'),
    ('Baby & Kids Store',                  'RETAIL'),
    ('Sports & Outdoor Store',             'RETAIL'),
    ('Musical Instrument Store',           'RETAIL'),
    ('Pet Shop & Supplies',               'RETAIL'),
    ('Optical Shop',                       'RETAIL'),
    ('Florist & Flower Shop',              'RETAIL'),
    ('Auto Parts & Accessories Shop',      'RETAIL'),
    ('Nursery & Plant Shop',               'RETAIL'),

    -- ---- Home & repair services -------------------------------------------
    ('Painting & Renovation',              'GENERAL'),
    ('Interior Design & Decor',            'GENERAL'),
    ('Carpenter & Furniture Repair',       'GENERAL'),
    ('Home Appliance Repair',              'GENERAL'),
    ('Mobile Phone Repair',                'GENERAL'),
    ('Computer & Laptop Repair',           'GENERAL'),
    ('Car Repair & Servicing',             'GENERAL'),
    ('Car Wash & Detailing',               'GENERAL'),
    ('Motorcycle Repair',                  'GENERAL'),
    ('Welding & Steel Fabrication',        'GENERAL'),
    ('CCTV & Security Installation',       'GENERAL'),
    ('Generator & Solar Solutions',        'GENERAL'),
    ('Borehole & Water Pump Services',     'GENERAL'),
    ('Gardening & Landscaping',            'GENERAL'),

    -- ---- Everyday services ---------------------------------------------
    ('Laundry & Dry Cleaning',             'GENERAL'),
    ('Tailor & Alterations',               'GENERAL'),
    ('Photography & Videography',          'GENERAL'),
    ('Event Management',                   'GENERAL'),
    ('Wedding Planning',                   'GENERAL'),
    ('Printing & Photocopy',               'GENERAL'),
    ('Courier & Parcel Service',           'GENERAL'),
    ('Packers & Movers',                   'GENERAL'),
    ('Gas Cylinder Supplier',              'GENERAL'),
    ('Water & Jar Supplier',               'GENERAL'),
    ('Equipment & Tools Rental',           'GENERAL'),
    ('Security Guard Services',            'GENERAL'),
    ('Cleaning Company',                   'GENERAL'),

    -- ---- Professional & office --------------------------------------------
    ('Real Estate Agency',                 'GENERAL'),
    ('Travel Agency',                      'GENERAL'),
    ('Car & Vehicle Rental',               'GENERAL'),
    ('Driving School',                     'GENERAL'),
    ('Coaching Centre & Private Tutor',    'GENERAL'),
    ('Skill & Language Training',          'GENERAL'),
    ('IT & Computer Training Institute',   'GENERAL'),
    ('Preschool & Kindergarten',           'GENERAL'),
    ('Daycare Centre',                     'GENERAL'),
    ('Law Firm & Legal Services',          'GENERAL'),
    ('Accounting & Tax Consultancy',       'GENERAL'),
    ('Business Consultancy',               'GENERAL'),
    ('Advertising & Marketing Agency',     'GENERAL'),
    ('Web & Software Development',          'GENERAL'),
    ('Graphic & Print Design Studio',      'GENERAL'),
    ('Architecture & Design Firm',         'GENERAL'),
    ('Engineering & Surveying Services',   'GENERAL'),
    ('Construction & Development Firm',    'GENERAL'),
    ('Internet Service Provider',          'GENERAL'),
    ('Mobile Financial Services Agent',    'GENERAL'),
    ('Insurance Agency',                   'GENERAL'),
    ('Money Exchange',                     'GENERAL'),
    ('NGO & Development Organisation',     'GENERAL'),

    -- ---- Hospitality & venues ------------------------------------------
    ('Hotel & Guest House',                'GENERAL'),
    ('Resort',                             'GENERAL'),
    ('Hostel & Bachelor Accommodation',    'GENERAL'),
    ('Community Centre & Convention Hall', 'GENERAL'),
    ('Co-working Space',                   'GENERAL'),
    ('Petrol Pump & CNG Station',          'GENERAL')
ON CONFLICT (name) DO NOTHING;
