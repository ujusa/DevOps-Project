"""Builds a small, deterministic NorthWind-style SQLite database (northwind.db).

The real assessment ships its own northwind.db; this one exists so the project
runs end to end. Swap in the real file and everything else keeps working.
"""
import os
import random
import sqlite3
from datetime import date, timedelta

HERE = os.path.dirname(os.path.abspath(__file__))
DB = os.path.join(HERE, "northwind.db")

CATEGORIES = ["Beverages", "Condiments", "Confections", "Dairy Products",
              "Grains/Cereals", "Meat/Poultry", "Produce", "Seafood"]
SUPPLIERS = [("Exotic Liquids", "UK"), ("New Orleans Cajun Delights", "USA"),
             ("Tokyo Traders", "Japan"), ("Pavlova, Ltd.", "Australia"),
             ("Heli Suesswaren", "Germany"), ("Formaggi Fortini", "Italy"),
             ("Norske Meierier", "Norway"), ("Ma Maison", "Canada")]
PRODUCTS = [  # name, category, supplier, unit price, discontinued
    ("Chai", 1, 1, 18.00, 0), ("Chang", 1, 1, 19.00, 0),
    ("Aniseed Syrup", 2, 1, 10.00, 0), ("Chef Anton's Gumbo Mix", 2, 2, 21.35, 1),
    ("Ikura", 8, 3, 31.00, 0), ("Konbu", 8, 3, 6.00, 0),
    ("Pavlova", 3, 4, 17.45, 0), ("Alice Mutton", 6, 4, 39.00, 1),
    ("Carnarvon Tigers", 8, 4, 62.50, 0), ("Schoggi Schokolade", 3, 5, 43.90, 0),
    ("NuNuCa Nuss-Nougat-Creme", 3, 5, 14.00, 0), ("Gorgonzola Telino", 4, 6, 12.50, 0),
    ("Mascarpone Fabioli", 4, 6, 32.00, 0), ("Geitost", 4, 7, 2.50, 0),
    ("Gudbrandsdalsost", 4, 7, 36.00, 0), ("Tourtiere", 6, 8, 7.45, 0),
    ("Pate chinois", 6, 8, 24.00, 0), ("Tofu", 7, 3, 23.25, 0),
    ("Manjimup Dried Apples", 7, 4, 53.00, 0), ("Gnocchi di nonna Alice", 5, 6, 38.00, 0),
    ("Ravioli Angelo", 5, 6, 19.50, 0), ("Singaporean Hokkien Fried Mee", 5, 3, 14.00, 1),
    ("Cote de Blaye", 1, 8, 263.50, 0), ("Ipoh Coffee", 1, 3, 46.00, 0),
]
CUSTOMERS = [  # id, company, contact, city, country
    ("ALFKI", "Alfreds Futterkiste", "Maria Anders", "Berlin", "Germany"),
    ("ANATR", "Ana Trujillo Emparedados", "Ana Trujillo", "Mexico D.F.", "Mexico"),
    ("ANTON", "Antonio Moreno Taqueria", "Antonio Moreno", "Mexico D.F.", "Mexico"),
    ("AROUT", "Around the Horn", "Thomas Hardy", "London", "UK"),
    ("BERGS", "Berglunds snabbkop", "Christina Berglund", "Lulea", "Sweden"),
    ("BLAUS", "Blauer See Delikatessen", "Hanna Moos", "Mannheim", "Germany"),
    ("BONAP", "Bon app'", "Laurence Lebihan", "Marseille", "France"),
    ("BOTTM", "Bottom-Dollar Markets", "Elizabeth Lincoln", "Tsawassen", "Canada"),
    ("BSBEV", "B's Beverages", "Victoria Ashworth", "London", "UK"),
    ("CHOPS", "Chop-suey Chinese", "Yang Wang", "Bern", "Switzerland"),
    ("ERNSH", "Ernst Handel", "Roland Mendel", "Graz", "Austria"),
    ("FOLKO", "Folk och fa HB", "Maria Larsson", "Bracke", "Sweden"),
    ("FRANK", "Frankenversand", "Peter Franken", "Munchen", "Germany"),
    ("GREAL", "Great Lakes Food Market", "Howard Snyder", "Eugene", "USA"),
    ("HUNGO", "Hungry Owl All-Night Grocers", "Patricia McKenna", "Cork", "Ireland"),
    ("LAZYK", "Lazy K Kountry Store", "John Steel", "Walla Walla", "USA"),
    ("QUICK", "QUICK-Stop", "Horst Kloss", "Cunewalde", "Germany"),
    ("RATTC", "Rattlesnake Canyon Grocery", "Paula Wilson", "Albuquerque", "USA"),
    ("SAVEA", "Save-a-lot Markets", "Jose Pavarotti", "Boise", "USA"),
    ("WHITC", "White Clover Markets", "Karl Jablonski", "Seattle", "USA"),
]
EMPLOYEES = [  # first, last, title, city, country, reports_to
    ("Nancy", "Davolio", "Sales Representative", "Seattle", "USA", 2),
    ("Andrew", "Fuller", "Vice President, Sales", "Tacoma", "USA", None),
    ("Janet", "Leverling", "Sales Representative", "Kirkland", "USA", 2),
    ("Margaret", "Peacock", "Sales Representative", "Redmond", "USA", 2),
    ("Steven", "Buchanan", "Sales Manager", "London", "UK", 2),
    ("Michael", "Suyama", "Sales Representative", "London", "UK", 5),
    ("Robert", "King", "Sales Representative", "London", "UK", 5),
    ("Laura", "Callahan", "Inside Sales Coordinator", "Seattle", "USA", 2),
    ("Anne", "Dodsworth", "Sales Representative", "London", "UK", 5),
]
SHIPPERS = ["Speedy Express", "United Package", "Federal Shipping"]


def build(path=DB, n_orders=400, seed=42):
    rng = random.Random(seed)
    if os.path.exists(path):
        os.remove(path)
    con = sqlite3.connect(path)
    c = con.cursor()
    c.executescript("""
    CREATE TABLE Categories (CategoryID INTEGER PRIMARY KEY, CategoryName TEXT);
    CREATE TABLE Suppliers (SupplierID INTEGER PRIMARY KEY, CompanyName TEXT, Country TEXT);
    CREATE TABLE Products (ProductID INTEGER PRIMARY KEY, ProductName TEXT, CategoryID INT,
        SupplierID INT, UnitPrice REAL, UnitsInStock INT, Discontinued INT);
    CREATE TABLE Customers (CustomerID TEXT PRIMARY KEY, CompanyName TEXT, ContactName TEXT,
        City TEXT, Country TEXT);
    CREATE TABLE Employees (EmployeeID INTEGER PRIMARY KEY, FirstName TEXT, LastName TEXT,
        Title TEXT, City TEXT, Country TEXT, ReportsTo INT);
    CREATE TABLE Shippers (ShipperID INTEGER PRIMARY KEY, CompanyName TEXT);
    CREATE TABLE Orders (OrderID INTEGER PRIMARY KEY, CustomerID TEXT, EmployeeID INT,
        OrderDate TEXT, ShippedDate TEXT, ShipVia INT, Freight REAL, ShipCity TEXT, ShipCountry TEXT);
    CREATE TABLE "Order Details" (OrderID INT, ProductID INT, UnitPrice REAL, Quantity INT,
        Discount REAL, PRIMARY KEY (OrderID, ProductID));
    """)
    c.executemany("INSERT INTO Categories VALUES (?,?)", list(enumerate(CATEGORIES, 1)))
    c.executemany("INSERT INTO Suppliers VALUES (?,?,?)",
                  [(i, n, co) for i, (n, co) in enumerate(SUPPLIERS, 1)])
    c.executemany("INSERT INTO Products VALUES (?,?,?,?,?,?,?)",
                  [(i, n, cat, sup, p, rng.randint(0, 120), d)
                   for i, (n, cat, sup, p, d) in enumerate(PRODUCTS, 1)])
    c.executemany("INSERT INTO Customers VALUES (?,?,?,?,?)", CUSTOMERS)
    c.executemany("INSERT INTO Employees VALUES (?,?,?,?,?,?,?)",
                  [(i, *e) for i, e in enumerate(EMPLOYEES, 1)])
    c.executemany("INSERT INTO Shippers VALUES (?,?)", list(enumerate(SHIPPERS, 1)))

    start = date(1996, 7, 1)
    for oid in range(10248, 10248 + n_orders):
        cust = rng.choice(CUSTOMERS)
        od = start + timedelta(days=rng.randint(0, 3 * 365))
        shipped = None if rng.random() < 0.05 else (od + timedelta(days=rng.randint(1, 30))).isoformat()
        c.execute("INSERT INTO Orders VALUES (?,?,?,?,?,?,?,?,?)",
                  (oid, cust[0], rng.randint(1, 9), od.isoformat(), shipped,
                   rng.randint(1, 3), round(rng.uniform(1, 300), 2), cust[3], cust[4]))
        for pid in rng.sample(range(1, len(PRODUCTS) + 1), rng.randint(1, 4)):
            c.execute('INSERT INTO "Order Details" VALUES (?,?,?,?,?)',
                      (oid, pid, PRODUCTS[pid - 1][3], rng.randint(1, 60),
                       rng.choice([0, 0, 0, 0.05, 0.1, 0.15, 0.2, 0.25])))
    con.commit()
    con.close()
    return path


if __name__ == "__main__":
    print("Built", build())
