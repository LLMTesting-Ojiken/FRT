SELECT QUOTE(UPPER(jsonb_set('{"a":1}', '$.a', 2)));
The result is: unistr('''Lu0017Au00132''')