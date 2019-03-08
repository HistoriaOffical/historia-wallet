#include <string>
#include <iostream>
#include <cassert>
#include "hashblock.h"
#include "uint256.h"
#include "utilstrencodings.h"

int testx16r() {
  std::cout << "Running Hash16R Test" << std::endl;

  std::string hashHex = "19bcdaa780349350b210ca84d73dc1c08fbae659990b47a9d28655e7e9be3970";
  //decimal order of hash16R is d28655e7e9be3970 hex converted to
  //13 2 8 6 5 5 14 7 14 9 11 14 3 9 7 0

  int expectedPositions[16] = {13, 2, 8, 6, 5, 5, 14, 7, 14, 9, 11, 14, 3, 9, 7, 0};

  uint256 *hash = new uint256();
  hash->SetHex(hashHex);
  uint256 hash256 = hash[0];

  if (hash256.GetHex() !=  hashHex)
    {
      std::cout << "Hashes differ!" << std::endl;
      std::cout << hash256.GetHex() << std::endl << hashHex << std::endl;
      return 1;
    }
      
  for (int i = 0; i < 15; i++)
    {
      int pos = GetHashSelection(hash256, i);
      if (expectedPositions[i] != pos)
	{
	  std::cout << "Unexpected position!" << std::endl;
	  std::cout << expectedPositions[i] << std::endl << pos << std::endl;
	  return 1;
	}
    }

  return 0;
}

int main() {
  bool fail = testx16r();

  if (!fail)
    std::cout << "Test passed" << std::endl;
}
