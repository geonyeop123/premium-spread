if redis.call('EXISTS', KEYS[1]) == 0 then
  return 0
end
local memberId = redis.call('HGET', KEYS[1], 'memberId')
if memberId ~= ARGV[5] then
  redis.call('DEL', KEYS[1])
  return 0
end
local familyId = redis.call('HGET', KEYS[1], 'familyId')
local generation = tonumber(redis.call('HGET', KEYS[1], 'generation'))
local expectedGeneration = tonumber(ARGV[2])
local currentHash = redis.call('HGET', KEYS[1], 'currentHash')
local currentJti = redis.call('HGET', KEYS[1], 'currentJti')
local previousHash = redis.call('HGET', KEYS[1], 'previousHash')
local previousJti = redis.call('HGET', KEYS[1], 'previousJti')
if familyId == false or generation == nil or currentHash == false or currentJti == false then
  redis.call('DEL', KEYS[1])
  return 0
end
if familyId ~= ARGV[1] or expectedGeneration == nil then
  return 0
end
local currentMatches = expectedGeneration == generation and currentHash == ARGV[3] and currentJti == ARGV[4]
local previousMatches = expectedGeneration == generation - 1 and previousHash == ARGV[3] and previousJti == ARGV[4]
if currentMatches or previousMatches then
  redis.call('DEL', KEYS[1])
  return 1
end
return 0
