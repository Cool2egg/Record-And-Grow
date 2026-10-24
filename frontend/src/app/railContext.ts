import { createContext, useContext } from 'react'

export const RailContext = createContext<{ openRail: () => void }>({
  openRail: () => {},
})

export function useRail() {
  return useContext(RailContext)
}
