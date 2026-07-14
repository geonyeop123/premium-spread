if redis.call('EXISTS', KEYS[1]) == 0 then
  return 5
end

local memberId = redis.call('HGET', KEYS[1], 'memberId')
if memberId ~= ARGV[1] then
  redis.call('DEL', KEYS[1])
  return 6
end

local familyId = redis.call('HGET', KEYS[1], 'familyId')
if familyId == false then
  redis.call('DEL', KEYS[1])
  return 6
end
if familyId ~= ARGV[4] then
  return 4
end

local currentHash = redis.call('HGET', KEYS[1], 'currentHash')
local currentJti = redis.call('HGET', KEYS[1], 'currentJti')
local currentGeneration = tonumber(redis.call('HGET', KEYS[1], 'generation'))
local expectedGeneration = tonumber(ARGV[5])
local redisTime = redis.call('TIME')
local now = tonumber(redisTime[1]) * 1000 + math.floor(tonumber(redisTime[2]) / 1000)
if currentHash == false or currentJti == false or currentGeneration == nil or expectedGeneration == nil then
  redis.call('DEL', KEYS[1])
  return 6
end

if currentHash == ARGV[2] and currentJti == ARGV[3] and currentGeneration == expectedGeneration then
  redis.call('HSET', KEYS[1],
    'previousHash', currentHash,
    'previousJti', currentJti,
    'rotatedAt', now,
    'currentHash', ARGV[6],
    'currentJti', ARGV[7],
    'generation', ARGV[8],
    'expiresAt', ARGV[9])
  redis.call('PEXPIRE', KEYS[1], ARGV[10])
  return 1
end

local previousHash = redis.call('HGET', KEYS[1], 'previousHash')
local previousJti = redis.call('HGET', KEYS[1], 'previousJti')
local rotatedAt = tonumber(redis.call('HGET', KEYS[1], 'rotatedAt'))
local grace = tonumber(ARGV[11])
if expectedGeneration == currentGeneration - 1 and previousHash == ARGV[2] and previousJti == ARGV[3] and
   rotatedAt ~= nil and now >= rotatedAt and now - rotatedAt <= grace then
  return 2
end

if expectedGeneration < currentGeneration then
  redis.call('DEL', KEYS[1])
  return 3
end

return 6
