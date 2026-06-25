create table t0 (c0 text);
create index i0 on t0 (c0(3) desc);
create index i0 on t0 (c0 desc(3));