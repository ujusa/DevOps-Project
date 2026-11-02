"""nw_api - the ONLY supported way for generated scripts to read NorthWind.

Every function returns plain Python data (lists of dicts with the column names
shown), so scripts never need SQL. Dates are ISO strings 'YYYY-MM-DD'.
"""
import os
import sqlite3

_DB = os.environ.get("NW_DB", os.path.join(os.path.dirname(os.path.abspath(__file__)), "northwind.db"))


def _query(sql, params=()):
    con = sqlite3.connect(f"file:{_DB}?mode=ro", uri=True)
    con.row_factory = sqlite3.Row
    try:
        return [dict(r) for r in con.execute(sql, params).fetchall()]
    finally:
        con.close()


def _where(filters):
    clauses, params = [], []
    for col, op, val in filters:
        if val is not None:
            clauses.append(f"{col} {op} ?")
            params.append(val)
    return (" WHERE " + " AND ".join(clauses)) if clauses else "", params


def get_customers(country=None, city=None):
    """Customers. Keys: CustomerID, CompanyName, ContactName, City, Country."""
    w, p = _where([("Country", "=", country), ("City", "=", city)])
    return _query("SELECT * FROM Customers" + w + " ORDER BY CustomerID", p)


def get_employees():
    """Employees. Keys: EmployeeID, FirstName, LastName, Title, City, Country, ReportsTo (EmployeeID or None)."""
    return _query("SELECT * FROM Employees ORDER BY EmployeeID")


def get_categories():
    """Product categories. Keys: CategoryID, CategoryName."""
    return _query("SELECT * FROM Categories ORDER BY CategoryID")


def get_suppliers(country=None):
    """Suppliers. Keys: SupplierID, CompanyName, Country."""
    w, p = _where([("Country", "=", country)])
    return _query("SELECT * FROM Suppliers" + w + " ORDER BY SupplierID", p)


def get_products(category_id=None, supplier_id=None, discontinued=None):
    """Products. Keys: ProductID, ProductName, CategoryID, SupplierID, UnitPrice, UnitsInStock,
    Discontinued (0/1). Pass discontinued=True/False to filter."""
    disc = None if discontinued is None else int(bool(discontinued))
    w, p = _where([("CategoryID", "=", category_id), ("SupplierID", "=", supplier_id),
                   ("Discontinued", "=", disc)])
    return _query("SELECT * FROM Products" + w + " ORDER BY ProductID", p)


def get_shippers():
    """Shippers. Keys: ShipperID, CompanyName."""
    return _query("SELECT * FROM Shippers ORDER BY ShipperID")


def get_orders(customer_id=None, employee_id=None, start_date=None, end_date=None, ship_country=None):
    """Orders. Keys: OrderID, CustomerID, EmployeeID, OrderDate, ShippedDate (None if unshipped),
    ShipVia (ShipperID), Freight, ShipCity, ShipCountry.
    start_date/end_date are inclusive 'YYYY-MM-DD' bounds on OrderDate."""
    w, p = _where([("CustomerID", "=", customer_id), ("EmployeeID", "=", employee_id),
                   ("OrderDate", ">=", start_date), ("OrderDate", "<=", end_date),
                   ("ShipCountry", "=", ship_country)])
    return _query("SELECT * FROM Orders" + w + " ORDER BY OrderID", p)


def get_order_details(order_id=None, product_id=None):
    """Order line items. Keys: OrderID, ProductID, UnitPrice, Quantity, Discount (0-1).
    Line revenue = UnitPrice * Quantity * (1 - Discount)."""
    w, p = _where([("OrderID", "=", order_id), ("ProductID", "=", product_id)])
    return _query('SELECT * FROM "Order Details"' + w + " ORDER BY OrderID, ProductID", p)
