redis.call('DEL', KEYS[1])
redis.call('HSET', KEYS[1],
  'memberId', ARGV[1],
  'currentHash', ARGV[2],
  'currentJti', ARGV[3],
  'familyId', ARGV[4],
  'generation', ARGV[5],
  'expiresAt', ARGV[6])
redis.call('PEXPIRE', KEYS[1], ARGV[7])
return 1
