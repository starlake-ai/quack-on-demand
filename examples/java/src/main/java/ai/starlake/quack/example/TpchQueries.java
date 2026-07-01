package ai.starlake.quack.example;

import java.util.List;

/**
 * The 22 TPC-H benchmark queries, with the standard validation substitution parameters from the
 * TPC-H specification (revision 2.x). Written in the DuckDB SQL dialect, which the Quack nodes
 * speak.
 *
 * <p>Every table reference is prefixed with the {@code $S.} placeholder so the target schema is
 * configurable; {@link #qualify} replaces {@code $S} with the schema name (default {@code tpch1}).
 * Q15, which the spec defines with a CREATE VIEW, is expressed here as a single statement using a
 * CTE so it fits one RPC.
 */
public final class TpchQueries {

  private TpchQueries() {}

  public record TpchQuery(int id, String title, String sql) {}

  /** Resolve the {@code $S.} schema placeholder in a query to a concrete schema name. */
  public static String qualify(String sql, String schema) {
    return sql.replace("$S", schema).strip();
  }

  public static final List<TpchQuery> ALL =
      List.of(
          new TpchQuery(
              1,
              "Pricing Summary Report",
              """
              select l_returnflag, l_linestatus,
                sum(l_quantity) as sum_qty,
                sum(l_extendedprice) as sum_base_price,
                sum(l_extendedprice * (1 - l_discount)) as sum_disc_price,
                sum(l_extendedprice * (1 - l_discount) * (1 + l_tax)) as sum_charge,
                avg(l_quantity) as avg_qty,
                avg(l_extendedprice) as avg_price,
                avg(l_discount) as avg_disc,
                count(*) as count_order
              from $S.lineitem
              where l_shipdate <= date '1998-12-01' - interval '90' day
              group by l_returnflag, l_linestatus
              order by l_returnflag, l_linestatus"""),
          new TpchQuery(
              2,
              "Minimum Cost Supplier",
              """
              select s_acctbal, s_name, n_name, p_partkey, p_mfgr, s_address, s_phone, s_comment
              from $S.part, $S.supplier, $S.partsupp, $S.nation, $S.region
              where p_partkey = ps_partkey and s_suppkey = ps_suppkey and p_size = 15
                and p_type like '%BRASS' and s_nationkey = n_nationkey
                and n_regionkey = r_regionkey and r_name = 'EUROPE'
                and ps_supplycost = (
                  select min(ps_supplycost)
                  from $S.partsupp, $S.supplier, $S.nation, $S.region
                  where p_partkey = ps_partkey and s_suppkey = ps_suppkey
                    and s_nationkey = n_nationkey and n_regionkey = r_regionkey
                    and r_name = 'EUROPE')
              order by s_acctbal desc, n_name, s_name, p_partkey
              limit 100"""),
          new TpchQuery(
              3,
              "Shipping Priority",
              """
              select l_orderkey,
                sum(l_extendedprice * (1 - l_discount)) as revenue,
                o_orderdate, o_shippriority
              from $S.customer, $S.orders, $S.lineitem
              where c_mktsegment = 'BUILDING' and c_custkey = o_custkey
                and l_orderkey = o_orderkey and o_orderdate < date '1995-03-15'
                and l_shipdate > date '1995-03-15'
              group by l_orderkey, o_orderdate, o_shippriority
              order by revenue desc, o_orderdate
              limit 10"""),
          new TpchQuery(
              4,
              "Order Priority Checking",
              """
              select o_orderpriority, count(*) as order_count
              from $S.orders
              where o_orderdate >= date '1993-07-01'
                and o_orderdate < date '1993-07-01' + interval '3' month
                and exists (
                  select * from $S.lineitem
                  where l_orderkey = o_orderkey and l_commitdate < l_receiptdate)
              group by o_orderpriority
              order by o_orderpriority"""),
          new TpchQuery(
              5,
              "Local Supplier Volume",
              """
              select n_name, sum(l_extendedprice * (1 - l_discount)) as revenue
              from $S.customer, $S.orders, $S.lineitem, $S.supplier, $S.nation, $S.region
              where c_custkey = o_custkey and l_orderkey = o_orderkey
                and l_suppkey = s_suppkey and c_nationkey = s_nationkey
                and s_nationkey = n_nationkey and n_regionkey = r_regionkey
                and r_name = 'ASIA' and o_orderdate >= date '1994-01-01'
                and o_orderdate < date '1994-01-01' + interval '1' year
              group by n_name
              order by revenue desc"""),
          new TpchQuery(
              6,
              "Forecasting Revenue Change",
              """
              select sum(l_extendedprice * l_discount) as revenue
              from $S.lineitem
              where l_shipdate >= date '1994-01-01'
                and l_shipdate < date '1994-01-01' + interval '1' year
                and l_discount between 0.06 - 0.01 and 0.06 + 0.01
                and l_quantity < 24"""),
          new TpchQuery(
              7,
              "Volume Shipping",
              """
              select supp_nation, cust_nation, l_year, sum(volume) as revenue
              from (
                select n1.n_name as supp_nation, n2.n_name as cust_nation,
                  extract(year from l_shipdate) as l_year,
                  l_extendedprice * (1 - l_discount) as volume
                from $S.supplier, $S.lineitem, $S.orders, $S.customer,
                     $S.nation n1, $S.nation n2
                where s_suppkey = l_suppkey and o_orderkey = l_orderkey
                  and c_custkey = o_custkey and s_nationkey = n1.n_nationkey
                  and c_nationkey = n2.n_nationkey
                  and ((n1.n_name = 'FRANCE' and n2.n_name = 'GERMANY')
                    or (n1.n_name = 'GERMANY' and n2.n_name = 'FRANCE'))
                  and l_shipdate between date '1995-01-01' and date '1996-12-31'
              ) as shipping
              group by supp_nation, cust_nation, l_year
              order by supp_nation, cust_nation, l_year"""),
          new TpchQuery(
              8,
              "National Market Share",
              """
              select o_year,
                sum(case when nation = 'BRAZIL' then volume else 0 end) / sum(volume) as mkt_share
              from (
                select extract(year from o_orderdate) as o_year,
                  l_extendedprice * (1 - l_discount) as volume, n2.n_name as nation
                from $S.part, $S.supplier, $S.lineitem, $S.orders, $S.customer,
                     $S.nation n1, $S.nation n2, $S.region
                where p_partkey = l_partkey and s_suppkey = l_suppkey
                  and l_orderkey = o_orderkey and o_custkey = c_custkey
                  and c_nationkey = n1.n_nationkey and n1.n_regionkey = r_regionkey
                  and r_name = 'AMERICA' and s_nationkey = n2.n_nationkey
                  and o_orderdate between date '1995-01-01' and date '1996-12-31'
                  and p_type = 'ECONOMY ANODIZED STEEL'
              ) as all_nations
              group by o_year
              order by o_year"""),
          new TpchQuery(
              9,
              "Product Type Profit Measure",
              """
              select nation, o_year, sum(amount) as sum_profit
              from (
                select n_name as nation, extract(year from o_orderdate) as o_year,
                  l_extendedprice * (1 - l_discount) - ps_supplycost * l_quantity as amount
                from $S.part, $S.supplier, $S.lineitem, $S.partsupp, $S.orders, $S.nation
                where s_suppkey = l_suppkey and ps_suppkey = l_suppkey
                  and ps_partkey = l_partkey and p_partkey = l_partkey
                  and o_orderkey = l_orderkey and s_nationkey = n_nationkey
                  and p_name like '%green%'
              ) as profit
              group by nation, o_year
              order by nation, o_year desc"""),
          new TpchQuery(
              10,
              "Returned Item Reporting",
              """
              select c_custkey, c_name,
                sum(l_extendedprice * (1 - l_discount)) as revenue,
                c_acctbal, n_name, c_address, c_phone, c_comment
              from $S.customer, $S.orders, $S.lineitem, $S.nation
              where c_custkey = o_custkey and l_orderkey = o_orderkey
                and o_orderdate >= date '1993-10-01'
                and o_orderdate < date '1993-10-01' + interval '3' month
                and l_returnflag = 'R' and c_nationkey = n_nationkey
              group by c_custkey, c_name, c_acctbal, c_phone, n_name, c_address, c_comment
              order by revenue desc
              limit 20"""),
          new TpchQuery(
              11,
              "Important Stock Identification",
              """
              select ps_partkey, sum(ps_supplycost * ps_availqty) as value
              from $S.partsupp, $S.supplier, $S.nation
              where ps_suppkey = s_suppkey and s_nationkey = n_nationkey
                and n_name = 'GERMANY'
              group by ps_partkey
              having sum(ps_supplycost * ps_availqty) > (
                select sum(ps_supplycost * ps_availqty) * 0.0001000000
                from $S.partsupp, $S.supplier, $S.nation
                where ps_suppkey = s_suppkey and s_nationkey = n_nationkey
                  and n_name = 'GERMANY')
              order by value desc"""),
          new TpchQuery(
              12,
              "Shipping Modes and Order Priority",
              """
              select l_shipmode,
                sum(case when o_orderpriority = '1-URGENT' or o_orderpriority = '2-HIGH'
                  then 1 else 0 end) as high_line_count,
                sum(case when o_orderpriority <> '1-URGENT' and o_orderpriority <> '2-HIGH'
                  then 1 else 0 end) as low_line_count
              from $S.orders, $S.lineitem
              where o_orderkey = l_orderkey and l_shipmode in ('MAIL', 'SHIP')
                and l_commitdate < l_receiptdate and l_shipdate < l_commitdate
                and l_receiptdate >= date '1994-01-01'
                and l_receiptdate < date '1994-01-01' + interval '1' year
              group by l_shipmode
              order by l_shipmode"""),
          new TpchQuery(
              13,
              "Customer Distribution",
              """
              select c_count, count(*) as custdist
              from (
                select c_custkey, count(o_orderkey) as c_count
                from $S.customer left outer join $S.orders
                  on c_custkey = o_custkey and o_comment not like '%special%requests%'
                group by c_custkey
              ) as c_orders
              group by c_count
              order by custdist desc, c_count desc"""),
          new TpchQuery(
              14,
              "Promotion Effect",
              """
              select 100.00 * sum(case when p_type like 'PROMO%'
                  then l_extendedprice * (1 - l_discount) else 0 end)
                / sum(l_extendedprice * (1 - l_discount)) as promo_revenue
              from $S.lineitem, $S.part
              where l_partkey = p_partkey and l_shipdate >= date '1995-09-01'
                and l_shipdate < date '1995-09-01' + interval '1' month"""),
          new TpchQuery(
              15,
              "Top Supplier",
              """
              with revenue (supplier_no, total_revenue) as (
                select l_suppkey, sum(l_extendedprice * (1 - l_discount))
                from $S.lineitem
                where l_shipdate >= date '1996-01-01'
                  and l_shipdate < date '1996-01-01' + interval '3' month
                group by l_suppkey
              )
              select s_suppkey, s_name, s_address, s_phone, total_revenue
              from $S.supplier, revenue
              where s_suppkey = supplier_no
                and total_revenue = (select max(total_revenue) from revenue)
              order by s_suppkey"""),
          new TpchQuery(
              16,
              "Parts/Supplier Relationship",
              """
              select p_brand, p_type, p_size, count(distinct ps_suppkey) as supplier_cnt
              from $S.partsupp, $S.part
              where p_partkey = ps_partkey and p_brand <> 'Brand#45'
                and p_type not like 'MEDIUM POLISHED%'
                and p_size in (49, 14, 23, 45, 19, 3, 36, 9)
                and ps_suppkey not in (
                  select s_suppkey from $S.supplier
                  where s_comment like '%Customer%Complaints%')
              group by p_brand, p_type, p_size
              order by supplier_cnt desc, p_brand, p_type, p_size"""),
          new TpchQuery(
              17,
              "Small-Quantity-Order Revenue",
              """
              select sum(l_extendedprice) / 7.0 as avg_yearly
              from $S.lineitem, $S.part
              where p_partkey = l_partkey and p_brand = 'Brand#23'
                and p_container = 'MED BOX'
                and l_quantity < (
                  select 0.2 * avg(l_quantity)
                  from $S.lineitem where l_partkey = p_partkey)"""),
          new TpchQuery(
              18,
              "Large Volume Customer",
              """
              select c_name, c_custkey, o_orderkey, o_orderdate, o_totalprice,
                sum(l_quantity)
              from $S.customer, $S.orders, $S.lineitem
              where o_orderkey in (
                  select l_orderkey from $S.lineitem
                  group by l_orderkey having sum(l_quantity) > 300)
                and c_custkey = o_custkey and o_orderkey = l_orderkey
              group by c_name, c_custkey, o_orderkey, o_orderdate, o_totalprice
              order by o_totalprice desc, o_orderdate
              limit 100"""),
          new TpchQuery(
              19,
              "Discounted Revenue",
              """
              select sum(l_extendedprice * (1 - l_discount)) as revenue
              from $S.lineitem, $S.part
              where (p_partkey = l_partkey and p_brand = 'Brand#12'
                  and p_container in ('SM CASE', 'SM BOX', 'SM PACK', 'SM PKG')
                  and l_quantity >= 1 and l_quantity <= 1 + 10 and p_size between 1 and 5
                  and l_shipmode in ('AIR', 'AIR REG') and l_shipinstruct = 'DELIVER IN PERSON')
                or (p_partkey = l_partkey and p_brand = 'Brand#23'
                  and p_container in ('MED BAG', 'MED BOX', 'MED PKG', 'MED PACK')
                  and l_quantity >= 10 and l_quantity <= 10 + 10 and p_size between 1 and 10
                  and l_shipmode in ('AIR', 'AIR REG') and l_shipinstruct = 'DELIVER IN PERSON')
                or (p_partkey = l_partkey and p_brand = 'Brand#34'
                  and p_container in ('LG CASE', 'LG BOX', 'LG PACK', 'LG PKG')
                  and l_quantity >= 20 and l_quantity <= 20 + 10 and p_size between 1 and 15
                  and l_shipmode in ('AIR', 'AIR REG') and l_shipinstruct = 'DELIVER IN PERSON')"""),
          new TpchQuery(
              20,
              "Potential Part Promotion",
              """
              select s_name, s_address
              from $S.supplier, $S.nation
              where s_suppkey in (
                  select ps_suppkey from $S.partsupp
                  where ps_partkey in (
                      select p_partkey from $S.part where p_name like 'forest%')
                    and ps_availqty > (
                      select 0.5 * sum(l_quantity) from $S.lineitem
                      where l_partkey = ps_partkey and l_suppkey = ps_suppkey
                        and l_shipdate >= date '1994-01-01'
                        and l_shipdate < date '1994-01-01' + interval '1' year))
                and s_nationkey = n_nationkey and n_name = 'CANADA'
              order by s_name"""),
          new TpchQuery(
              21,
              "Suppliers Who Kept Orders Waiting",
              """
              select s_name, count(*) as numwait
              from $S.supplier, $S.lineitem l1, $S.orders, $S.nation
              where s_suppkey = l1.l_suppkey and o_orderkey = l1.l_orderkey
                and o_orderstatus = 'F' and l1.l_receiptdate > l1.l_commitdate
                and exists (
                  select * from $S.lineitem l2
                  where l2.l_orderkey = l1.l_orderkey and l2.l_suppkey <> l1.l_suppkey)
                and not exists (
                  select * from $S.lineitem l3
                  where l3.l_orderkey = l1.l_orderkey and l3.l_suppkey <> l1.l_suppkey
                    and l3.l_receiptdate > l3.l_commitdate)
                and s_nationkey = n_nationkey and n_name = 'SAUDI ARABIA'
              group by s_name
              order by numwait desc, s_name
              limit 100"""),
          new TpchQuery(
              22,
              "Global Sales Opportunity",
              """
              select cntrycode, count(*) as numcust, sum(c_acctbal) as totacctbal
              from (
                select substring(c_phone from 1 for 2) as cntrycode, c_acctbal
                from $S.customer
                where substring(c_phone from 1 for 2) in ('13', '31', '23', '29', '30', '18', '17')
                  and c_acctbal > (
                    select avg(c_acctbal) from $S.customer
                    where c_acctbal > 0.00
                      and substring(c_phone from 1 for 2) in ('13', '31', '23', '29', '30', '18', '17'))
                  and not exists (
                    select * from $S.orders where o_custkey = c_custkey)
              ) as custsale
              group by cntrycode
              order by cntrycode"""));
}