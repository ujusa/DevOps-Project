"""Writes src/test/resources/ground_truth.json (questions + reference scripts) and verifies each runs."""
import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import runner  # noqa: E402

Q = [
 ("q01", "count", "How many customers are there in total?",
  "result = len(nw_api.get_customers())"),
 ("q02", "count", "How many customers are based in Germany?",
  "result = len(nw_api.get_customers(country='Germany'))"),
 ("q03", "count", "How many distinct customers placed at least one order in 1997?",
  "result = len({o['CustomerID'] for o in nw_api.get_orders(start_date='1997-01-01', end_date='1997-12-31')})"),
 ("q04", "count", "How many orders have not been shipped yet?",
  "result = sum(1 for o in nw_api.get_orders() if o['ShippedDate'] is None)"),
 ("q05", "list", "List the names of all discontinued products.",
  "result = sorted(p['ProductName'] for p in nw_api.get_products(discontinued=True))"),
 ("q06", "list", "Which countries do our suppliers come from?",
  "result = sorted({s['Country'] for s in nw_api.get_suppliers()})"),
 ("q07", "scalar", "What is the most expensive product?",
  "result = max(nw_api.get_products(), key=lambda p: p['UnitPrice'])['ProductName']"),
 ("q08", "number", "What was the total revenue (after discounts) in 1997?",
  """ids = {o['OrderID'] for o in nw_api.get_orders(start_date='1997-01-01', end_date='1997-12-31')}
result = round(sum(d['UnitPrice'] * d['Quantity'] * (1 - d['Discount'])
                   for d in nw_api.get_order_details() if d['OrderID'] in ids), 2)"""),
 ("q09", "scalar", "Which product category generated the most revenue in 1997?",
  """from collections import defaultdict
ids = {o['OrderID'] for o in nw_api.get_orders(start_date='1997-01-01', end_date='1997-12-31')}
cat_of = {p['ProductID']: p['CategoryID'] for p in nw_api.get_products()}
names = {c['CategoryID']: c['CategoryName'] for c in nw_api.get_categories()}
rev = defaultdict(float)
for d in nw_api.get_order_details():
    if d['OrderID'] in ids:
        rev[cat_of[d['ProductID']]] += d['UnitPrice'] * d['Quantity'] * (1 - d['Discount'])
result = names[max(rev, key=rev.get)]"""),
 ("q10", "scalar", "Which employee (full name) handled the most orders?",
  """from collections import Counter
c = Counter(o['EmployeeID'] for o in nw_api.get_orders())
emp = {e['EmployeeID']: e['FirstName'] + ' ' + e['LastName'] for e in nw_api.get_employees()}
result = emp[c.most_common(1)[0][0]]"""),
 ("q11", "map", "How many orders did each shipper deliver? Return shipper name -> count.",
  """from collections import Counter
c = Counter(o['ShipVia'] for o in nw_api.get_orders())
result = {s['CompanyName']: c.get(s['ShipperID'], 0) for s in nw_api.get_shippers()}"""),
 ("q12", "number", "What is the average freight cost per order, rounded to 2 decimals?",
  """orders = nw_api.get_orders()
result = round(sum(o['Freight'] for o in orders) / len(orders), 2)"""),
 ("q13", "list", "Who are the top 3 customers (company name) by number of orders?",
  """from collections import Counter
c = Counter(o['CustomerID'] for o in nw_api.get_orders())
names = {x['CustomerID']: x['CompanyName'] for x in nw_api.get_customers()}
result = [names[cid] for cid, _ in sorted(c.items(), key=lambda kv: (-kv[1], kv[0]))[:3]]"""),
 ("q14", "list", "Which employees report directly to Andrew Fuller? Give full names.",
  """emps = nw_api.get_employees()
boss = next(e['EmployeeID'] for e in emps if e['FirstName'] == 'Andrew' and e['LastName'] == 'Fuller')
result = sorted(e['FirstName'] + ' ' + e['LastName'] for e in emps if e['ReportsTo'] == boss)"""),
 ("q15", "number", "How many units of 'Chai' have been sold in total?",
  """pid = next(p['ProductID'] for p in nw_api.get_products() if p['ProductName'] == 'Chai')
result = sum(d['Quantity'] for d in nw_api.get_order_details(product_id=pid))"""),
]

out = []
for qid, cat, question, script in Q:
    res = runner.run(script)
    out.append({"id": qid, "category": cat, "question": question, "script": script})
    print(qid, json.dumps(res)[:90])

path = os.path.join(HERE, "..", "src", "test", "resources", "ground_truth.json")
with open(path, "w", encoding="utf-8") as f:
    json.dump(out, f, indent=2)
print("wrote", os.path.normpath(path))
